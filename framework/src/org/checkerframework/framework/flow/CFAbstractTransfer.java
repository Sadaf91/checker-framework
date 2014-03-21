package org.checkerframework.framework.flow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;

import org.checkerframework.dataflow.analysis.ConditionalTransferResult;
import org.checkerframework.dataflow.analysis.FlowExpressions;
import org.checkerframework.dataflow.analysis.RegularTransferResult;
import org.checkerframework.dataflow.analysis.TransferFunction;
import org.checkerframework.dataflow.analysis.TransferInput;
import org.checkerframework.dataflow.analysis.TransferResult;
import org.checkerframework.dataflow.analysis.FlowExpressions.ClassName;
import org.checkerframework.dataflow.analysis.FlowExpressions.FieldAccess;
import org.checkerframework.dataflow.analysis.FlowExpressions.LocalVariable;
import org.checkerframework.dataflow.analysis.FlowExpressions.Receiver;
import org.checkerframework.dataflow.analysis.FlowExpressions.ThisReference;
import org.checkerframework.dataflow.cfg.UnderlyingAST;
import org.checkerframework.dataflow.cfg.UnderlyingAST.CFGMethod;
import org.checkerframework.dataflow.cfg.UnderlyingAST.Kind;
import org.checkerframework.dataflow.cfg.node.AbstractNodeVisitor;
import org.checkerframework.dataflow.cfg.node.ArrayAccessNode;
import org.checkerframework.dataflow.cfg.node.AssignmentNode;
import org.checkerframework.dataflow.cfg.node.CaseNode;
import org.checkerframework.dataflow.cfg.node.ClassNameNode;
import org.checkerframework.dataflow.cfg.node.ConditionalNotNode;
import org.checkerframework.dataflow.cfg.node.EqualToNode;
import org.checkerframework.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.dataflow.cfg.node.InstanceOfNode;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.NarrowingConversionNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.NotEqualNode;
import org.checkerframework.dataflow.cfg.node.StringConcatenateAssignmentNode;
import org.checkerframework.dataflow.cfg.node.StringConversionNode;
import org.checkerframework.dataflow.cfg.node.TernaryExpressionNode;
import org.checkerframework.dataflow.cfg.node.VariableDeclarationNode;
import org.checkerframework.dataflow.cfg.node.WideningConversionNode;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.GenericAnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.util.ContractsUtils;
import org.checkerframework.framework.util.FlowExpressionParseUtil;
import org.checkerframework.framework.util.FlowExpressionParseUtil.FlowExpressionContext;
import org.checkerframework.framework.util.FlowExpressionParseUtil.FlowExpressionParseException;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.InternalUtils;
import org.checkerframework.javacutil.Pair;
import org.checkerframework.javacutil.TreeUtils;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;

/**
 * The default analysis transfer function for the Checker Framework propagates
 * information through assignments and uses the {@link AnnotatedTypeFactory} to
 * provide checker-specific logic how to combine types (e.g., what is the type
 * of a string concatenation, given the types of the two operands) and as an
 * abstraction function (e.g., determine the annotations on literals).
 * <p>
 *
 * Design note:  CFAbstractTransfer and its subclasses are supposed to act
 * as transfer functions.  But, since the AnnotatedTypeFactory already
 * existed and performed checker-independent type propagation,
 * CFAbstractTransfer delegates work to it instead of duplicating some
 * logic in CFAbstractTransfer.  The checker-specific subclasses of
 * CFAbstractTransfer do implement transfer function logic themselves.
 *
 * @author Charlie Garrett
 * @author Stefan Heule
 */
public abstract class CFAbstractTransfer<V extends CFAbstractValue<V>,
            S extends CFAbstractStore<V, S>,
            T extends CFAbstractTransfer<V, S, T>>
        extends AbstractNodeVisitor<TransferResult<V, S>, TransferInput<V, S>>
        implements TransferFunction<V, S> {

    /**
     * The analysis class this store belongs to.
     */
    protected CFAbstractAnalysis<V, S, T> analysis;

    /**
     * Should the analysis use sequential Java semantics (i.e., assume that only
     * one thread is running at all times)?
     */
    protected final boolean sequentialSemantics;

    public CFAbstractTransfer(CFAbstractAnalysis<V, S, T> analysis) {
        this.analysis = analysis;
        this.sequentialSemantics = !analysis.checker.hasOption("concurrentSemantics");
    }

    /**
     * This method is called before returning the abstract value {@code value}
     * as the result of the transfer function. By default, the value is not
     * changed but subclasses might decide to implement some functionality. The
     * store at this position is also passed.
     */
    protected V finishValue(V value, S store) {
        return value;
    }

    /**
     * This method is called before returning the abstract value {@code value}
     * as the result of the transfer function. By default, the value is not
     * changed but subclasses might decide to implement some functionality. The
     * store at this position is also passed (two stores, as the result is a
     * {@link ConditionalTransferResult}.
     */
    protected V finishValue(V value, S thenStore, S elseStore) {
        return value;
    }

    /**
     * @return The abstract value of a non-leaf tree {@code tree}, as computed
     *         by the {@link AnnotatedTypeFactory}.
     */
    protected V getValueFromFactory(Tree tree, Node node) {
        GenericAnnotatedTypeFactory<V, S, T, ? extends CFAbstractAnalysis<V, S, T>> factory = analysis.atypeFactory;
        analysis.setCurrentTree(tree);
        // is there an assignment context node available?
        if (node != null && node.getAssignmentContext() != null) {
            // get the declared type of the assignment context by looking up the
            // assignment context tree's type in the factory while flow is
            // disabled.
            // Note: Since we use getAnnotatedType(Element), flow is not used in
            // any case, so we would not have to disable it.
            boolean oldFlow = factory.getUseFlow();
            factory.setUseFlow(false);
            Element element = node.getAssignmentContext().getElementForType();
            if (element != null) {
                AnnotatedTypeMirror assCtxt = factory.getAnnotatedType(element);
                if (assCtxt instanceof AnnotatedExecutableType) {
                    // For a MethodReturnContext, we get the full type of the
                    // method, but we only want the return type.
                    assCtxt = ((AnnotatedExecutableType) assCtxt)
                            .getReturnType();
                }
                factory.getVisitorState().setAssignmentContext(
                        Pair.of(node.getAssignmentContext().getContextTree(),
                                assCtxt));
            }
            factory.setUseFlow(oldFlow);
        }
        AnnotatedTypeMirror at = factory.getAnnotatedType(tree);
        analysis.setCurrentTree(null);
        factory.getVisitorState().setAssignmentContext(null);
        return analysis.createAbstractValue(at);
    }

    /**
     * @return An abstract value with the given {@code type} and the annotations
     *         from {@code annotatedValue}.
     */
    protected V getValueWithSameAnnotations(TypeMirror type, V annotatedValue) {
        if (annotatedValue == null) return null;
        GenericAnnotatedTypeFactory<V, S, T, ? extends CFAbstractAnalysis<V, S, T>> factory = analysis.atypeFactory;
        AnnotatedTypeMirror at = factory.toAnnotatedType(type);
        at.replaceAnnotations(annotatedValue.getType().getAnnotations());
        return analysis.createAbstractValue(at);
    }

    private S fixedInitialStore = null;
    /**
     * Set a fixed initial Store.
     */
    public void setFixedInitialStore(S s) {
        fixedInitialStore = s;
    }

    /**
     * The initial store maps method formal parameters to their currently most
     * refined type.
     */
    @Override
    public S initialStore(UnderlyingAST underlyingAST,
            /*@Nullable */ List<LocalVariableNode> parameters) {
        if (fixedInitialStore != null) return fixedInitialStore;

        S info = analysis.createEmptyStore(sequentialSemantics);

        if (underlyingAST.getKind() == Kind.METHOD) {
            AnnotatedTypeFactory factory = analysis.getTypeFactory();
            for (LocalVariableNode p : parameters) {
                AnnotatedTypeMirror anno = factory.getAnnotatedType(p
                        .getElement());
                info.initializeMethodParameter(p,
                        analysis.createAbstractValue(anno));
            }

            // add properties known through precondition
            CFGMethod method = (CFGMethod) underlyingAST;
            MethodTree methodTree = method.getMethod();
            ExecutableElement methodElem = TreeUtils
                    .elementFromDeclaration(methodTree);
            addInformationFromPreconditions(info, factory, method, methodTree,
                    methodElem);

            // Add knowledge about final fields, or values of non-final fields
            // if we are inside a constructor (information about initializers)
            final ClassTree classTree = method.getClassTree();
            TypeMirror classType = InternalUtils.typeOf(classTree);
            List<Pair<VariableElement, V>> fieldValues = analysis
                    .getFieldValues();
            boolean isNotFullyInitializedReceiver = isNotFullyInitializedReceiver(methodTree);
            for (Pair<VariableElement, V> p : fieldValues) {
                VariableElement element = p.first;
                V value = p.second;
                if (ElementUtils.isFinal(element)
                        || TreeUtils.isConstructor(methodTree)) {
                    TypeMirror fieldType = ElementUtils.getType(element);
                    Receiver receiver;
                    if (ElementUtils.isStatic(element)) {
                        receiver = new ClassName(classType);
                    } else {
                        receiver = new ThisReference(classType);
                    }
                    Receiver field = new FieldAccess(receiver, fieldType,
                            element);
                    info.insertValue(field, value);
                }
            }

            // add properties about fields (static information from type)
            for (Tree member : classTree.getMembers()) {
                if (member instanceof VariableTree) {
                    VariableTree vt = (VariableTree) member;
                    final VariableElement element = TreeUtils
                            .elementFromDeclaration(vt);
                    AnnotatedTypeMirror type = factory
                            .getAnnotatedType(element);
                    TypeMirror fieldType = ElementUtils.getType(element);
                    Receiver receiver;
                    if (ElementUtils.isStatic(element)) {
                        receiver = new ClassName(classType);
                    } else {
                        receiver = new ThisReference(classType);
                    }
                    V value = analysis.createAbstractValue(type);
                    if (value == null) continue;
                    if (isNotFullyInitializedReceiver) {
                        // if we are in a constructor (or another method where
                        // the receiver might not yet be fully initialized),
                        // then we can still use the static type, but only
                        // if there is also an initializer that already does
                        // some initialization.
                        boolean found = false;
                        for (Pair<VariableElement, V> fieldValue : fieldValues) {
                            if (fieldValue.first.equals(element)) {
                                value = value.leastUpperBound(fieldValue.second);
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            // no initializer found, cannot use static type
                            continue;
                        }
                    }
                    Receiver field = new FieldAccess(receiver, fieldType,
                            element);
                    info.insertValue(field, value);
                }
            }

            // add information about effectively final variables (from outer scopes)
            for (Entry<Element, V> e : analysis.atypeFactory.getFinalLocalValues().entrySet()) {
                LocalVariable l = new LocalVariable(e.getKey());
                info.insertValue(l, e.getValue());
            }
        }

        return info;
    }

    /**
     * Returns true if the receiver of a method might not yet be fully
     * initialized.
     */
    protected boolean isNotFullyInitializedReceiver(MethodTree methodTree) {
        return TreeUtils.isConstructor(methodTree);
    }

    /**
     * Add the information from all the preconditions of the method
     * {@code method} with corresponding tree {@code methodTree} to the store
     * {@code info}.
     */
    protected void addInformationFromPreconditions(S info,
            AnnotatedTypeFactory factory, CFGMethod method,
            MethodTree methodTree, ExecutableElement methodElement) {
        ContractsUtils contracts = ContractsUtils.getInstance(analysis.atypeFactory);
        FlowExpressionContext flowExprContext = null;
        Set<Pair<String, String>> preconditions = contracts
                .getPreconditions(methodElement);

        for (Pair<String, String> p : preconditions) {
            String expression = p.first;
            AnnotationMirror annotation = AnnotationUtils.fromName(analysis
                    .getTypeFactory().getElementUtils(), p.second);

            // Only check if the postcondition concerns this checker
            if (!analysis.getTypeFactory().isSupportedQualifier(annotation)) {
                continue;
            }
            if (flowExprContext == null) {
                flowExprContext = FlowExpressionParseUtil
                        .buildFlowExprContextForDeclaration(methodTree,
                                method.getClassTree(), analysis.getTypeFactory());
            }

            FlowExpressions.Receiver expr = null;
            try {
                // TODO: currently, these expressions are parsed at the
                // declaration (i.e. here) and for every use. this could
                // be optimized to store the result the first time.
                // (same for other annotations)
                expr = FlowExpressionParseUtil.parse(expression,
                        flowExprContext,
                        analysis.atypeFactory.getPath(methodTree));
                info.insertValue(expr, annotation);
            } catch (FlowExpressionParseException e) {
                // report errors here
                analysis.checker.report(e.getResult(), methodTree);
            }
        }
    }

    /**
     * The default visitor returns the input information unchanged, or in the
     * case of conditional input information, merged.
     */
    @Override
    public TransferResult<V, S> visitNode(Node n, TransferInput<V, S> in) {
        V value = null;

        // TODO: handle implicit/explicit this and go to correct factory method
        Tree tree = n.getTree();
        if (tree != null) {
            if (TreeUtils.canHaveTypeAnnotation(tree)) {
                value = getValueFromFactory(tree, n);
            }
        }

        if (in.containsTwoStores()) {
            S thenStore = in.getThenStore();
            S elseStore = in.getElseStore();
            return new ConditionalTransferResult<>(finishValue(value,
                    thenStore, elseStore), thenStore, elseStore);
        } else {
            S info = in.getRegularStore();
            return new RegularTransferResult<>(finishValue(value, info), info);
        }
    }

    @Override
    public TransferResult<V, S> visitClassName(ClassNameNode n,
            TransferInput<V, S> in) {
        // The tree underlying a class name is a type tree.
        V value = null;

        Tree tree = n.getTree();
        if (tree != null) {
            if (TreeUtils.canHaveTypeAnnotation(tree)) {
                GenericAnnotatedTypeFactory<V, S, T,
                        ? extends CFAbstractAnalysis<V, S, T>> factory = analysis.atypeFactory;
                analysis.setCurrentTree(tree);
                AnnotatedTypeMirror at = factory.getAnnotatedTypeFromTypeTree(tree);
                analysis.setCurrentTree(null);
                value = analysis.createAbstractValue(at);
            }
        }

        if (in.containsTwoStores()) {
            S thenStore = in.getThenStore();
            S elseStore = in.getElseStore();
            return new ConditionalTransferResult<>(finishValue(value,
                    thenStore, elseStore), thenStore, elseStore);
        } else {
            S info = in.getRegularStore();
            return new RegularTransferResult<>(finishValue(value, info), info);
        }
    }

    @Override
    public TransferResult<V, S> visitFieldAccess(FieldAccessNode n,
            TransferInput<V, S> p) {
        S store = p.getRegularStore();
        V storeValue = store.getValue(n);
        // look up value in factory, and take the more specific one
        // TODO: handle cases, where this is not allowed (e.g. contructors in
        // non-null type systems)
        V factoryValue = getValueFromFactory(n.getTree(), n);
        V value = moreSpecificValue(factoryValue, storeValue);
        return new RegularTransferResult<>(finishValue(value, store), store);
    }

    @Override
    public TransferResult<V, S> visitArrayAccess(ArrayAccessNode n,
            TransferInput<V, S> p) {
        S store = p.getRegularStore();
        V storeValue = store.getValue(n);
        // look up value in factory, and take the more specific one
        V factoryValue = getValueFromFactory(n.getTree(), n);
        V value = moreSpecificValue(factoryValue, storeValue);
        return new RegularTransferResult<>(finishValue(value, store), store);
    }

    /**
     * Use the most specific type information available according to the store.
     */
    @Override
    public TransferResult<V, S> visitLocalVariable(LocalVariableNode n,
            TransferInput<V, S> in) {
        S store = in.getRegularStore();
        V valueFromStore = store.getValue(n);
        V valueFromFactory = getValueFromFactory(n.getTree(), n);
        V value = moreSpecificValue(valueFromFactory, valueFromStore);
        return new RegularTransferResult<>(finishValue(value, store), store);
    }

    /**
     * The resulting abstract value is the merge of the 'then' and 'else'
     * branch.
     */
    @Override
    public TransferResult<V, S> visitTernaryExpression(TernaryExpressionNode n,
            TransferInput<V, S> p) {
        TransferResult<V, S> result = super.visitTernaryExpression(n, p);
        S store = result.getRegularStore();
        V thenValue = p.getValueOfSubNode(n.getThenOperand());
        V elseValue = p.getValueOfSubNode(n.getElseOperand());
        V resultValue = null;
        if (thenValue != null && elseValue != null) {
            resultValue = thenValue.leastUpperBound(elseValue);
        }
        return new RegularTransferResult<>(finishValue(resultValue, store),
                store);
    }

    /**
     * Revert the role of the 'thenStore' and 'elseStore'.
     */
    @Override
    public TransferResult<V, S> visitConditionalNot(ConditionalNotNode n,
            TransferInput<V, S> p) {
        TransferResult<V, S> result = super.visitConditionalNot(n, p);
        S thenStore = result.getThenStore();
        S elseStore = result.getElseStore();
        return new ConditionalTransferResult<>(result.getResultValue(),
                elseStore, thenStore);
    }

    @Override
    public TransferResult<V, S> visitEqualTo(EqualToNode n,
            TransferInput<V, S> p) {
        TransferResult<V, S> res = super.visitEqualTo(n, p);

        Node leftN = n.getLeftOperand();
        Node rightN = n.getRightOperand();
        V leftV = p.getValueOfSubNode(leftN);
        V rightV = p.getValueOfSubNode(rightN);

        // if annotations differ, use the one that is more precise for both
        // sides (and add it to the store if possible)
        res = strengthenAnnotationOfEqualTo(res, leftN, rightN, leftV, rightV,
                false);
        res = strengthenAnnotationOfEqualTo(res, rightN, leftN, rightV, leftV,
                false);
        return res;
    }

    @Override
    public TransferResult<V, S> visitNotEqual(NotEqualNode n,
            TransferInput<V, S> p) {
        TransferResult<V, S> res = super.visitNotEqual(n, p);

        Node leftN = n.getLeftOperand();
        Node rightN = n.getRightOperand();
        V leftV = p.getValueOfSubNode(leftN);
        V rightV = p.getValueOfSubNode(rightN);

        // if annotations differ, use the one that is more precise for both
        // sides (and add it to the store if possible)
        res = strengthenAnnotationOfEqualTo(res, leftN, rightN, leftV, rightV,
                true);
        res = strengthenAnnotationOfEqualTo(res, rightN, leftN, rightV, leftV,
                true);

        return res;
    }

    /**
     * Refine the annotation of {@code secondNode} if the annotation
     * {@code secondValue} is less precise than {@code firstvalue}. This is
     * possible, if {@code secondNode} is an expression that is tracked by the
     * store (e.g., a local variable or a field).
     *
     * @param res
     *            The previous result.
     * @param notEqualTo
     *            If true, indicates that the logic is flipped (i.e., the
     *            information is added to the {@code elseStore} instead of the
     *            {@code thenStore}) for a not-equal comparison.
     * @return The conditional transfer result (if information has been added),
     *         or {@code null}.
     */
    protected TransferResult<V, S> strengthenAnnotationOfEqualTo(
            TransferResult<V, S> res, Node firstNode, Node secondNode,
            V firstValue, V secondValue, boolean notEqualTo) {
        if (firstValue != null) {
            // Only need to insert if the second value is actually different.
            if (!firstValue.equals(secondValue)) {
                List<Node> secondParts = splitAssignments(secondNode);
                for (Node secondPart : secondParts) {
                    Receiver secondInternal = FlowExpressions.internalReprOf(
                            analysis.getTypeFactory(), secondPart);
                    if (CFAbstractStore.canInsertReceiver(secondInternal)) {
                        S thenStore = res.getThenStore();
                        S elseStore = res.getElseStore();
                        if (notEqualTo) {
                            elseStore.insertValue(secondInternal, firstValue);
                        } else {
                            thenStore.insertValue(secondInternal, firstValue);
                        }
                        return new ConditionalTransferResult<>(
                                res.getResultValue(), thenStore, elseStore);
                    }
                }
            }
        }
        return res;
    }

    /**
     * Takes a node, and either returns the node itself again (as a singleton
     * list), or if the node is an assignment node, returns the lhs and rhs
     * (where splitAssignments is applied recursively to the rhs).
     */
    protected List<Node> splitAssignments(Node node) {
        if (node instanceof AssignmentNode) {
            List<Node> result = new ArrayList<>();
            AssignmentNode a = (AssignmentNode) node;
            result.add(a.getTarget());
            result.addAll(splitAssignments(a.getExpression()));
            return result;
        } else {
            return Collections.singletonList(node);
        }
    }

    @Override
    public TransferResult<V, S> visitAssignment(AssignmentNode n,
            TransferInput<V, S> in) {
        Node lhs = n.getTarget();
        Node rhs = n.getExpression();

        S info = in.getRegularStore();
        V rhsValue = in.getValueOfSubNode(rhs);
        processCommonAssignment(in, lhs, rhs, info, rhsValue);

        return new RegularTransferResult<>(finishValue(rhsValue, info), info);
    }

    @Override
    public TransferResult<V, S> visitStringConcatenateAssignment(
            StringConcatenateAssignmentNode n, TransferInput<V, S> in) {
        TransferResult<V, S> result = super.visitStringConcatenateAssignment(n, in);
        Node lhs = n.getLeftOperand();
        Node rhs = n.getRightOperand();

        // update the results store if the assignment target is something we can
        // process
        S info = result.getRegularStore();
        V rhsValue = result.getResultValue();
        processCommonAssignment(in, lhs, rhs, info, rhsValue);

        return result;
    }

    /**
     * Determine abstract value of right-hand side and update the store
     * accordingly to the assignment.
     */
    protected void processCommonAssignment(TransferInput<V, S> in, Node lhs,
            Node rhs, S info, V rhsValue) {

        // update information in the store
        info.updateForAssignment(lhs, rhsValue);
    }

    @Override
    public TransferResult<V, S> visitMethodInvocation(MethodInvocationNode n,
            TransferInput<V, S> in) {

        S store = in.getRegularStore();
        ExecutableElement method = n.getTarget().getMethod();

        V factoryValue = null;

        Tree tree = n.getTree();
        if (tree != null) {
            // look up the value from factory
            factoryValue = getValueFromFactory(tree, n);
        }
        // look up the value in the store (if possible)
        V storeValue = store.getValue(n);
        V resValue = moreSpecificValue(factoryValue, storeValue);

        store.updateForMethodCall(n, analysis.atypeFactory, resValue);

        // add new information based on postcondition
        processPostconditions(n, store, method, tree);

        S thenStore = store;
        S elseStore = thenStore.copy();

        // add new information based on conditional postcondition
        processConditionalPostconditions(n, method, tree, thenStore, elseStore);

        return new ConditionalTransferResult<>(finishValue(resValue, thenStore,
                elseStore), thenStore, elseStore);
    }

    /**
     * Add information based on all postconditions of method {@code n} with tree
     * {@code tree} and element {@code method} to the store {@code store}.
     */
    protected void processPostconditions(MethodInvocationNode n, S store,
            ExecutableElement methodElement, Tree tree) {
        ContractsUtils contracts = ContractsUtils
                .getInstance(analysis.atypeFactory);
        Set<Pair<String, String>> postconditions = contracts
                .getPostconditions(methodElement);

        FlowExpressionContext flowExprContext = null;

        for (Pair<String, String> p : postconditions) {
            String expression = p.first;
            AnnotationMirror anno = AnnotationUtils.fromName(analysis
                    .getTypeFactory().getElementUtils(), p.second);

            // Only check if the postcondition concerns this checker
            if (!analysis.getTypeFactory().isSupportedQualifier(anno)) {
                continue;
            }
            if (flowExprContext == null) {
                flowExprContext = FlowExpressionParseUtil
                        .buildFlowExprContextForUse(n, analysis.getTypeFactory());
            }

            try {
                FlowExpressions.Receiver r = FlowExpressionParseUtil.parse(
                        expression, flowExprContext,
                        analysis.atypeFactory.getPath(tree));
                store.insertValue(r, anno);
            } catch (FlowExpressionParseException e) {
                // these errors are reported at the declaration, ignore here
            }
        }
    }

    /**
     * Add information based on all conditional postconditions of method
     * {@code n} with tree {@code tree} and element {@code method} to the
     * appropriate store.
     */
    protected void processConditionalPostconditions(MethodInvocationNode n,
            ExecutableElement methodElement, Tree tree, S thenStore, S elseStore) {
        ContractsUtils contracts = ContractsUtils
                .getInstance(analysis.atypeFactory);
        Set<Pair<String, Pair<Boolean, String>>> conditionalPostconditions = contracts
                .getConditionalPostconditions(methodElement);

        FlowExpressionContext flowExprContext = null;

        for (Pair<String, Pair<Boolean, String>> p : conditionalPostconditions) {
            String expression = p.first;
            AnnotationMirror anno = AnnotationUtils.fromName(analysis
                    .getTypeFactory().getElementUtils(), p.second.second);
            boolean result = p.second.first;

            // Only check if the postcondition concerns this checker
            if (!analysis.getTypeFactory().isSupportedQualifier(anno)) {
                continue;
            }
            if (flowExprContext == null) {
                flowExprContext = FlowExpressionParseUtil
                        .buildFlowExprContextForUse(n, analysis.getTypeFactory());
            }

            try {
                FlowExpressions.Receiver r = FlowExpressionParseUtil.parse(
                        expression, flowExprContext,
                        analysis.atypeFactory.getPath(tree));
                if (result) {
                    thenStore.insertValue(r, anno);
                } else {
                    elseStore.insertValue(r, anno);
                }
            } catch (FlowExpressionParseException e) {
                // these errors are reported at the declaration, ignore here
            }
        }
    }

    /**
     * A case produces no value, but it may imply some facts about the argument
     * to the switch statement.
     */
    @Override
    public TransferResult<V, S> visitCase(CaseNode n, TransferInput<V, S> in) {
        S store = in.getRegularStore();
        return new RegularTransferResult<>(finishValue(null, store), store);
    }

    /**
     * In a cast {@code (@A C) e} of some expression {@code e} to a new type
     * {@code @A C}, we usually take the annotation of the type {@code C} (here
     * {@code @A}). However, if the inferred annotation of {@code e} is more
     * precise, we keep that one.
     */
    // @Override
    // public TransferResult<V, S> visitTypeCast(TypeCastNode n,
    // TransferInput<V, S> p) {
    // TransferResult<V, S> result = super.visitTypeCast(n, p);
    // V value = result.getResultValue();
    // V operandValue = p.getValueOfSubNode(n.getOperand());
    // // Normally we take the value of the type cast node. However, if the old
    // // flow-refined value was more precise, we keep that value.
    // V resultValue = moreSpecificValue(value, operandValue);
    // result.setResultValue(resultValue);
    // return result;
    // }

    /**
     * Refine the operand of an instanceof check with more specific annotations
     * if possible.
     */
    @Override
    public TransferResult<V, S> visitInstanceOf(InstanceOfNode n,
            TransferInput<V, S> p) {
        TransferResult<V, S> result = super.visitInstanceOf(n, p);

        // Look at the annotations from the type of the instanceof check
        // (provided by the factory)
        V factoryValue = getValueFromFactory(n.getTree().getType(), null);

        // Look at the value from the operand.
        V operandValue = p.getValueOfSubNode(n.getOperand());

        // Combine the two.
        V mostPreciseValue = moreSpecificValue(factoryValue, operandValue);

        // Insert into the store if possible.
        Receiver operandInternal = FlowExpressions.internalReprOf(
                analysis.getTypeFactory(), n.getOperand());
        if (CFAbstractStore.canInsertReceiver(operandInternal)) {
            S thenStore = result.getThenStore();
            S elseStore = result.getElseStore();
            thenStore.insertValue(operandInternal, mostPreciseValue);
            return new ConditionalTransferResult<>(result.getResultValue(),
                    thenStore, elseStore);
        }

        return result;
    }

    /**
     * Returns the abstract value of {@code (value1, value2)} that is more
     * specific. If the two are incomparable, then {@code value1} is returned.
     */
    public V moreSpecificValue(V value1, V value2) {
        if (value1 == null) {
            return value2;
        }
        if (value2 == null) {
            return value1;
        }
        return value1.mostSpecific(value2, value1);
    }

    @Override
    public TransferResult<V, S> visitVariableDeclaration(
            VariableDeclarationNode n, TransferInput<V, S> p) {
        S store = p.getRegularStore();
        return new RegularTransferResult<>(finishValue(null, store), store);
    }

    @Override
    public TransferResult<V, S> visitNarrowingConversion(
            NarrowingConversionNode n, TransferInput<V, S> p) {
        TransferResult<V, S> result = super.visitNarrowingConversion(n, p);
        // Combine annotations from the operand with the narrow type
        V operandValue = p.getValueOfSubNode(n.getOperand());
        V narrowedValue = getValueWithSameAnnotations(n.getType(), operandValue);
        result.setResultValue(narrowedValue);
        return result;
    }

    @Override
    public TransferResult<V, S> visitWideningConversion(
            WideningConversionNode n, TransferInput<V, S> p) {
        TransferResult<V, S> result = super.visitWideningConversion(n, p);
        // Combine annotations from the operand with the wide type
        V operandValue = p.getValueOfSubNode(n.getOperand());
        V widenedValue = getValueWithSameAnnotations(n.getType(), operandValue);
        result.setResultValue(widenedValue);
        return result;
    }

    @Override
    public TransferResult<V, S> visitStringConversion(StringConversionNode n,
            TransferInput<V, S> p) {
        TransferResult<V, S> result = super.visitStringConversion(n, p);
        result.setResultValue(p.getValueOfSubNode(n.getOperand()));
        return result;
    }
}