package org.eclipse.jdt.internal.corext.refactoring.code;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;

import org.eclipse.jdt.internal.corext.Assert;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.ASTRewrite;
import org.eclipse.jdt.internal.corext.dom.Bindings;
import org.eclipse.jdt.internal.corext.dom.HierarchicalASTVisitor;
import org.eclipse.jdt.internal.corext.refactoring.Checks;
import org.eclipse.jdt.internal.corext.refactoring.base.Context;
import org.eclipse.jdt.internal.corext.refactoring.base.IChange;
import org.eclipse.jdt.internal.corext.refactoring.base.JavaSourceContext;
import org.eclipse.jdt.internal.corext.refactoring.base.Refactoring;
import org.eclipse.jdt.internal.corext.refactoring.base.RefactoringStatus;
import org.eclipse.jdt.internal.corext.refactoring.changes.CompilationUnitChange;
import org.eclipse.jdt.internal.corext.refactoring.changes.TextChange;
import org.eclipse.jdt.internal.corext.refactoring.rename.TempDeclarationFinder;
import org.eclipse.jdt.internal.corext.refactoring.rename.TempOccurrenceFinder;
import org.eclipse.jdt.internal.corext.refactoring.util.ResourceUtil;
import org.eclipse.jdt.internal.corext.textmanipulation.MultiTextEdit;
import org.eclipse.jdt.internal.corext.textmanipulation.TextBuffer;
import org.eclipse.jdt.internal.corext.textmanipulation.TextEdit;

public class PromoteTempToFieldRefactoring extends Refactoring {

	private final int fSelectionStart;
    private final int fSelectionLength;
    private final ICompilationUnit fCu;
	
	public static final int INITIALIZE_IN_FIELD= 0;
	public static final int INITIALIZE_IN_METHOD= 1;
	public static final int INITIALIZE_IN_CONSTRUCTOR= 2;
	
	//------ settings ---------//
	private String fFieldName;
	private int fVisibility; 	/*see Modifier*/
	private boolean fDeclareStatic;
	private boolean fDeclareFinal;
	private int fInitializeIn; /*see INITIALIZE_IN_* constaints */

	//------ fields used for computations ---------//
    private CompilationUnit fCompilationUnitNode;
    private VariableDeclaration fTempDeclarationNode;
	
	public PromoteTempToFieldRefactoring(ICompilationUnit cu, int selectionStart, int selectionLength){
		Assert.isTrue(selectionStart >= 0);
		Assert.isTrue(selectionLength >= 0);
		Assert.isTrue(cu.exists());
		fSelectionStart= selectionStart;
		fSelectionLength= selectionLength;
		fCu= cu;
		
        fFieldName= "";
        fVisibility= Modifier.PRIVATE;
        fDeclareStatic= false;
        fDeclareFinal= false;
        fInitializeIn= INITIALIZE_IN_METHOD;
	}
	
    /*
     * @see org.eclipse.jdt.internal.corext.refactoring.base.IRefactoring#getName()
     */
    public String getName() {
        return "Promote Local Variable to Field";
    }
    
    public int[] getAvailableVisibilities(){
    	return new int[]{Modifier.PUBLIC, Modifier.PROTECTED, Modifier.NONE, Modifier.PRIVATE};
    }
    
    public int getVisibility() {
        return fVisibility;
    }

    public boolean getDeclareFinal() {
        return fDeclareFinal;
    }

    public boolean getDeclareStatic() {
        return fDeclareStatic;
    }

    public String getFieldName() {
        return fFieldName;
    }

    public int getInitializeIn() {
        return fInitializeIn;
    }

    public void setVisibility(int accessModifier) {
    	Assert.isTrue(accessModifier == Modifier.PRIVATE ||
    					accessModifier == Modifier.NONE ||
    					accessModifier == Modifier.PROTECTED ||
    					accessModifier == Modifier.PUBLIC);
        fVisibility= accessModifier;
    }

    public void setDeclareFinal(boolean declareFinal) {
        fDeclareFinal= declareFinal;
    }

    public void setDeclareStatic(boolean declareStatic) {
        fDeclareStatic= declareStatic;
    }

    public void setFieldName(String fieldName) {
    	Assert.isNotNull(fieldName);
        fFieldName= fieldName;
    }

    public void setInitializeIn(int initializeIn) {
    	Assert.isTrue(	initializeIn == INITIALIZE_IN_CONSTRUCTOR ||
    					initializeIn == INITIALIZE_IN_FIELD ||
    					initializeIn == INITIALIZE_IN_METHOD);
        fInitializeIn= initializeIn;
    }
	
	public boolean canEnableSettingStatic(){
		return fInitializeIn != INITIALIZE_IN_CONSTRUCTOR &&
				! isTempDeclaredInStaticMethod();
	}
	
	public boolean canEnableSettingFinal(){
		if (fInitializeIn == INITIALIZE_IN_CONSTRUCTOR)
			return  canEnableSettingDeclareInConstructors() && ! tempHasAssignmentsOtherThanInitialization();
		else if (fInitializeIn == INITIALIZE_IN_FIELD)	
			return  canEnableSettingDeclareInFieldDeclaration() && ! tempHasAssignmentsOtherThanInitialization();
		else	
			return false;
	}
	
    private boolean tempHasAssignmentsOtherThanInitialization() {
    	TempAssignmentFinder assignmentFinder= new TempAssignmentFinder(fTempDeclarationNode);
    	fCompilationUnitNode.accept(assignmentFinder);
		return assignmentFinder.hasAssignments();
    }
	
	public boolean canEnableSettingDeclareInConstructors(){
		return ! fDeclareStatic &&
				! getMethodDeclaration().isConstructor() &&
				! isDeclaredInAnonymousClass() && 
				! isTempDeclaredInStaticMethod() && 
				tempHasInitializer();
	}
	
	public boolean canEnableSettingDeclareInMethod(){
		return ! fDeclareFinal && 
				tempHasInitializer();
	}
    private boolean tempHasInitializer() {
        return getTempInitializer() != null;
    }

	public boolean canEnableSettingDeclareInFieldDeclaration(){
		return tempHasInitializer();
	}

    private Expression getTempInitializer() {
    	return fTempDeclarationNode.getInitializer();
    }

    private boolean isTempDeclaredInStaticMethod() {
    	return Modifier.isStatic(getMethodDeclaration().getModifiers());
    }
    
    private MethodDeclaration getMethodDeclaration(){
    	return (MethodDeclaration)ASTNodes.getParent(fTempDeclarationNode, MethodDeclaration.class);
    }

    private boolean isDeclaredInAnonymousClass() {
    	return null != ASTNodes.getParent(fTempDeclarationNode, AnonymousClassDeclaration.class);
    }
	
    /*
     * @see org.eclipse.jdt.internal.corext.refactoring.base.Refactoring#checkActivation(org.eclipse.core.runtime.IProgressMonitor)
     */
    public RefactoringStatus checkActivation(IProgressMonitor pm) throws JavaModelException {
    	try{
    		
    		RefactoringStatus result= Checks.validateModifiesFiles(ResourceUtil.getFiles(new ICompilationUnit[]{fCu}));
			if (result.hasFatalError())
				return result;

    		initAST();

			if (fTempDeclarationNode == null)
				return RefactoringStatus.createFatalErrorStatus("Select a declaration or a reference to a local variable.");
			
			if (! Checks.isDeclaredInMethod(fTempDeclarationNode))
				return RefactoringStatus.createFatalErrorStatus("Currently, only local variables declared in methods can be converted to fields.");
			
			if (isMethodParameter())
				return RefactoringStatus.createFatalErrorStatus("Cannot convert method parameters to fields.");

			if (isTempAnExceptionInCatchBlock())
				return RefactoringStatus.createFatalErrorStatus("Cannot convert exceptions declared in catch clauses to fields.");
	
			result.merge(checkTempTypeForLocalTypeUsage());
			if (result.hasFatalError())
		        return result;
		     
		    initializeDefaults();
	        return result;
		} catch (CoreException e){	
			throw new JavaModelException(e);
    	} finally {
    		pm.done();
    	}
    }
    
    private void initializeDefaults() {
		fFieldName= fTempDeclarationNode.getName().getIdentifier();
        fVisibility= Modifier.PRIVATE;
        fDeclareStatic= Modifier.isStatic(getMethodDeclaration().getModifiers());
        fDeclareFinal= false;
        if (canEnableSettingDeclareInMethod())
	        fInitializeIn= INITIALIZE_IN_METHOD;
	    else if (canEnableSettingDeclareInFieldDeclaration())    
	        fInitializeIn= INITIALIZE_IN_FIELD;
	    else if (canEnableSettingDeclareInConstructors())    
	        fInitializeIn= INITIALIZE_IN_CONSTRUCTOR;
    }
        
    private RefactoringStatus checkTempInitializerForLocalTypeUsage() {
    	Expression initializer= fTempDeclarationNode.getInitializer();
    	if (initializer == null)
	        return null;
	    
	    LocalTypeAndVariableUsageAnalyzer localTypeAnalyer= new LocalTypeAndVariableUsageAnalyzer();
	    initializer.accept(localTypeAnalyer);
	    //do not create a list - it is not shown in a dialog anyway.
	    //can optimize here to not walk the whole expression but only until 1 match is found
	    if (! localTypeAnalyer.getLocalTypeUsageNodes().isEmpty())
			return RefactoringStatus.createFatalErrorStatus("Cannot promote this local variable to a field because it uses types or variables declared locally in the method");
        return null;
    }
    
    private RefactoringStatus checkTempTypeForLocalTypeUsage(){
    	VariableDeclarationStatement vds= getTempDeclarationStatement();
    	if (vds == null)
    		return RefactoringStatus.createFatalErrorStatus("Cannot promote this local variable to a field");
    	Type type= 	vds.getType();
    	ITypeBinding binding= type.resolveBinding();
    	if (binding == null)
    		return RefactoringStatus.createFatalErrorStatus("Cannot promote this local variable to a field");
    	if (binding.isLocal())
			return RefactoringStatus.createFatalErrorStatus("Cannot promote this local variable to a field because it uses a type declared locally in the method");
		return null;    	
    }
    
    private VariableDeclarationStatement getTempDeclarationStatement() {
        return (VariableDeclarationStatement) ASTNodes.getParent(fTempDeclarationNode, VariableDeclarationStatement.class);
    }
    
    private boolean isTempAnExceptionInCatchBlock() {
		return (fTempDeclarationNode.getParent() instanceof CatchClause);
    }
    
    private boolean isMethodParameter() {
    	return (fTempDeclarationNode.getParent() instanceof MethodDeclaration);
    }
    
	private void initAST(){
		fCompilationUnitNode= AST.parseCompilationUnit(fCu, true);
		fTempDeclarationNode= TempDeclarationFinder.findTempDeclaration(fCompilationUnitNode, fSelectionStart, fSelectionLength);
	}

	public RefactoringStatus validateInput(){
		return Checks.checkFieldName(fFieldName);
	}
	
    /*
     * @see org.eclipse.jdt.internal.corext.refactoring.base.Refactoring#checkInput(org.eclipse.core.runtime.IProgressMonitor)
     */
    public RefactoringStatus checkInput(IProgressMonitor pm) throws JavaModelException {
    	try{
	        RefactoringStatus result= new RefactoringStatus();
	        
	        if (fInitializeIn != INITIALIZE_IN_METHOD)
	        	result.merge(checkTempInitializerForLocalTypeUsage());
	        if (result.hasFatalError())
	        	return result;
	        
	        result.merge(checkClashesWithExistingFields());  
	        if (fInitializeIn == INITIALIZE_IN_CONSTRUCTOR)
		        result.merge(checkClashesInConstructors());  
	        return result;
    	} finally {
    		pm.done();
    	}
    }
    private RefactoringStatus checkClashesInConstructors() {
    	Assert.isTrue(fInitializeIn == INITIALIZE_IN_CONSTRUCTOR);
    	Assert.isTrue(! isDeclaredInAnonymousClass());
    	TypeDeclaration declaringType= (TypeDeclaration)getMethodDeclaration().getParent();
		MethodDeclaration[] methods= declaringType.getMethods();
		for (int i= 0; i < methods.length; i++) {
            MethodDeclaration method= methods[i];
            if (! method.isConstructor())
            	continue;
            NameCollector nameCollector= new NameCollector(method){
            	protected boolean visitNode(ASTNode node) {
            		return true;
            	}
            };	
            method.accept(nameCollector);
            List names= nameCollector.getNames();
            if (names.contains(fFieldName)){
            	String pattern= "Name conflict with name ''{0}'' used in ''{1}''";
 				String msg= MessageFormat.format(pattern, new String[]{fFieldName, Bindings.asString(method.resolveBinding())});
            	return RefactoringStatus.createFatalErrorStatus(msg);
            }	
        }    	
    	return null;
    }
    
    private RefactoringStatus checkClashesWithExistingFields(){
        FieldDeclaration[] existingFields= getFieldDeclarationsInDeclaringType();
        for (int i= 0; i < existingFields.length; i++) {
            FieldDeclaration declaration= existingFields[i];
			VariableDeclarationFragment[] fragments= (VariableDeclarationFragment[]) declaration.fragments().toArray(new VariableDeclarationFragment[declaration.fragments().size()]);
			for (int j= 0; j < fragments.length; j++) {
                VariableDeclarationFragment fragment= fragments[j];
                if (fFieldName.equals(fragment.getName().getIdentifier())){
                	//cannot conflict with more than 1 name
                	Context context= JavaSourceContext.create(fCu, fragment);
                	return RefactoringStatus.createFatalErrorStatus("Name conflict with existing field", context);
                }
            }
        }
        return null;
    }
    
    private FieldDeclaration[] getFieldDeclarationsInDeclaringType() {
		return getFieldDeclarations(getBodyDeclarationListOfDeclaringType());
    }
    
    private List getBodyDeclarationListOfDeclaringType(){
    	ASTNode methodParent= getMethodDeclaration().getParent();
    	if (methodParent instanceof TypeDeclaration)
    		return ((TypeDeclaration)methodParent).bodyDeclarations();
    	if (methodParent instanceof AnonymousClassDeclaration)
    		return ((AnonymousClassDeclaration)methodParent).bodyDeclarations();
    	Assert.isTrue(false);
    	return null;	
    }
    
    private static FieldDeclaration[] getFieldDeclarations(List bodyDeclarations) {
    	List fields= new ArrayList(1);
    	for (Iterator iter= bodyDeclarations.iterator(); iter.hasNext();) {
	        Object each= iter.next();
	        if (each instanceof FieldDeclaration)
	        	fields.add(each);
        }
        return (FieldDeclaration[]) fields.toArray(new FieldDeclaration[fields.size()]);
    }

    /*
     * @see org.eclipse.jdt.internal.corext.refactoring.base.IRefactoring#createChange(org.eclipse.core.runtime.IProgressMonitor)
     */
    public IChange createChange(IProgressMonitor pm) throws JavaModelException {
    	pm.beginTask("", 1);
    	try{
    		ASTRewrite rewrite= new ASTRewrite(fCompilationUnitNode);
    		addFieldDeclaration(rewrite);
    		if (fInitializeIn == INITIALIZE_IN_METHOD && tempHasInitializer())
    			addLocalDeclarationSplit(rewrite);
			else
				addLocalDeclarationRemoval(rewrite);
    		if (fInitializeIn == INITIALIZE_IN_CONSTRUCTOR)
    			addInitializersToConstructors(rewrite);
    		if (! fFieldName.equals(fTempDeclarationNode.getName().getIdentifier()))	
    			addTempRenames(rewrite);
            return createChange(rewrite);
    	} catch (CoreException e) {
    		throw new JavaModelException(e);
        } finally{
    		pm.done();
    	}
    }
    
    private void addTempRenames(ASTRewrite rewrite) {
    	ASTNode[] tempRefs= TempOccurrenceFinder.findTempOccurrenceNodes(fTempDeclarationNode, true, false);
		for (int j= 0; j < tempRefs.length; j++) {
			ASTNode occurence= tempRefs[j];
			if (occurence instanceof SimpleName){
				SimpleName newName= getAST().newSimpleName(fFieldName);
				rewrite.markAsReplaced(occurence, newName);
			}
		}
    }
    
    private void addInitializersToConstructors(ASTRewrite rewrite) throws JavaModelException {
    	Assert.isTrue(! isDeclaredInAnonymousClass());
    	TypeDeclaration typeDeclaration= (TypeDeclaration)getMethodDeclaration().getParent();
    	MethodDeclaration[] allConstructors= getAllConstructors(typeDeclaration);
    	if (allConstructors.length == 0){
            addNewConstructorWithInitializing(rewrite, typeDeclaration);
    	} else {
    		for (int i= 0; i < allConstructors.length; i++) {
                MethodDeclaration constructor= allConstructors[i];
                if (shouldInsertTempInitialization(constructor))
                    addFieldInitializationToConstructor(rewrite, constructor);
            }
    	}
    }
    private void addNewConstructorWithInitializing(ASTRewrite rewrite, TypeDeclaration typeDeclaration) throws JavaModelException {
        MethodDeclaration newConstructor= getAST().newMethodDeclaration();
        newConstructor.setConstructor(true);
        newConstructor.setExtraDimensions(0);
        newConstructor.setModifiers(getModifiersForDefaultConstructor(typeDeclaration));
        newConstructor.setName(getAST().newSimpleName(typeDeclaration.getName().getIdentifier()));
        addFieldInitializationToConstructor(rewrite, newConstructor);
        rewrite.markAsInserted(newConstructor);
        int constructorInsertIndex= computeInsertIndexForNewConstructor(typeDeclaration);
        typeDeclaration.bodyDeclarations().add(constructorInsertIndex, newConstructor);
    }
    
    private int computeInsertIndexForNewConstructor(TypeDeclaration typeDeclaration) {
    	List bodyDeclarations= typeDeclaration.bodyDeclarations();
    	if (bodyDeclarations.isEmpty())
	        return 0;
		int firstMethodIndex= findFirstMethodIndex(typeDeclaration);
		if (firstMethodIndex == -1)
			return bodyDeclarations.size();
		else	
			return firstMethodIndex;
    }
    
    private int findFirstMethodIndex(TypeDeclaration typeDeclaration) {
    	for (int i= 0, n= typeDeclaration.bodyDeclarations().size(); i < n; i++) {
            if (typeDeclaration.bodyDeclarations().get(i) instanceof MethodDeclaration)
            	return i;
        }
        return -1;
    }
    
    private void addFieldInitializationToConstructor(ASTRewrite rewrite, MethodDeclaration constructor) throws JavaModelException {
    	if (constructor.getBody() == null)
	    	constructor.setBody(getAST().newBlock());
        List statements= constructor.getBody().statements();
        ExpressionStatement initialization= createExpressionStatementThatInitializesField(rewrite);
        rewrite.markAsInserted(initialization);
        statements.add(initialization);
    }
    
    private static int getModifiersForDefaultConstructor(TypeDeclaration typeDeclaration) {
    	if (Modifier.isPublic(typeDeclaration.getModifiers()))
    		return Modifier.PUBLIC;
    	else if (Modifier.isProtected(typeDeclaration.getModifiers()))
    		return Modifier.PROTECTED;
		else if (Modifier.isPrivate(typeDeclaration.getModifiers()))
    		return Modifier.PRIVATE;
        else
			return Modifier.NONE;
    }
    
    private static boolean shouldInsertTempInitialization(MethodDeclaration constructor){
    	Assert.isTrue(constructor.isConstructor());
        if (constructor.getBody() == null)
        	return false;
        List statements= constructor.getBody().statements();
        if (statements == null) 
        	return false;
        if (statements.size() > 0 && statements.get(0) instanceof ConstructorInvocation)
        	return false;
		return true;        
    }
    
    private static MethodDeclaration[] getAllConstructors(TypeDeclaration typeDeclaration){
    	MethodDeclaration[] allMethods= typeDeclaration.getMethods();
    	List result= new ArrayList(Math.min(allMethods.length, 1));
		for (int i= 0; i < allMethods.length; i++) {
            MethodDeclaration declaration= allMethods[i];
            if (declaration.isConstructor())
            	result.add(declaration);
        }    	
    	return (MethodDeclaration[]) result.toArray(new MethodDeclaration[result.size()]);
    }
    
    private IChange createChange(ASTRewrite rewrite) throws CoreException{
        TextChange change= new CompilationUnitChange("", fCu);
        TextBuffer textBuffer= TextBuffer.create(fCu.getBuffer().getContents());
        TextEdit resultingEdits= new MultiTextEdit();
        rewrite.rewriteNode(textBuffer, resultingEdits, null);
        change.addTextEdit("Promote local variable to field", resultingEdits);
        rewrite.removeModifications();
        return change;
    }

    private void addLocalDeclarationSplit(ASTRewrite rewrite) throws JavaModelException {
    	VariableDeclarationStatement tempDeclarationStatement= getTempDeclarationStatement();
    	Block block= (Block)tempDeclarationStatement.getParent();//XXX can it be anything else?
    	int statementIndex= block.statements().indexOf(tempDeclarationStatement);
   	   	Assert.isTrue(statementIndex != -1);
    	List fragments= tempDeclarationStatement.fragments();
        int fragmentIndex= fragments.indexOf(fTempDeclarationNode);
    	Assert.isTrue(fragmentIndex != -1);

        for (int i1= fragmentIndex, n = fragments.size(); i1 < n; i1++) {
        	VariableDeclarationFragment fragment= (VariableDeclarationFragment)fragments.get(i1);
        	rewrite.markAsRemoved(fragment);
        }
        if (fragmentIndex == 0)
           	rewrite.markAsRemoved(tempDeclarationStatement);
        
        Assert.isTrue(tempHasInitializer());
        ExpressionStatement assignmentStatement= createExpressionStatementThatInitializesField(rewrite);
        rewrite.markAsInserted(assignmentStatement);
        block.statements().add(statementIndex + 1, assignmentStatement);
        
        if (fragmentIndex + 1 < fragments.size()){
            VariableDeclarationFragment firstFragmentAfter= (VariableDeclarationFragment)fragments.get(fragmentIndex + 1);
            VariableDeclarationFragment copyfirstFragmentAfter= (VariableDeclarationFragment)rewrite.createCopy(firstFragmentAfter);
        	VariableDeclarationStatement statement= getAST().newVariableDeclarationStatement(copyfirstFragmentAfter);
        	for (int i= fragmentIndex + 2; i < fragments.size(); i++) {
        		VariableDeclarationFragment fragment= (VariableDeclarationFragment)fragments.get(i);
                VariableDeclarationFragment fragmentCopy= (VariableDeclarationFragment)rewrite.createCopy(fragment);
                rewrite.markAsInserted(fragmentCopy);
                statement.fragments().add(fragmentCopy);
            }
            rewrite.markAsInserted(statement);
            block.statements().add(statementIndex + 2, statement);
        }
    }
    
    private ExpressionStatement createExpressionStatementThatInitializesField(ASTRewrite rewrite) throws JavaModelException{
        Assignment assignment= getAST().newAssignment();
        SimpleName fieldName= getAST().newSimpleName(fFieldName);
        assignment.setLeftHandSide(fieldName);
        String initializerCode= fCu.getBuffer().getText(getTempInitializer().getStartPosition(), getTempInitializer().getLength());
        Expression tempInitializerCopy= (Expression)rewrite.createPlaceholder(initializerCode, ASTRewrite.EXPRESSION);
        ///XXX workaround for bug 25178
        ///(Expression)rewrite.createCopy(getTempInitializer());
        assignment.setRightHandSide(tempInitializerCopy);
        ExpressionStatement assignmentStatement= getAST().newExpressionStatement(assignment);
        return assignmentStatement;
    }

    private void addLocalDeclarationRemoval(ASTRewrite rewrite) {
		VariableDeclarationStatement tempDeclarationStatement= getTempDeclarationStatement();
    	List fragments= tempDeclarationStatement.fragments();

    	int fragmentIndex= fragments.indexOf(fTempDeclarationNode);
    	Assert.isTrue(fragmentIndex != -1);
        VariableDeclarationFragment fragment= (VariableDeclarationFragment)fragments.get(fragmentIndex);
        rewrite.markAsRemoved(fragment);
        if (fragments.size() == 1)
			rewrite.markAsRemoved(tempDeclarationStatement);
    }

    private void addFieldDeclaration(ASTRewrite rewrite) {
    	List bodyDeclarations= getBodyDeclarationListOfDeclaringType();
    	FieldDeclaration[] fields= getFieldDeclarationsInDeclaringType();
    	int insertIndex;
    	if (fields.length == 0)
    		insertIndex= 0;
    	else
    		insertIndex= bodyDeclarations.indexOf(fields[fields.length - 1]) + 1;
    	
    	FieldDeclaration fieldDeclaration= createNewFieldDeclaration(rewrite);
    	rewrite.markAsInserted(fieldDeclaration);
    	bodyDeclarations.add(insertIndex, fieldDeclaration);	
    }
    
    private FieldDeclaration createNewFieldDeclaration(ASTRewrite rewrite) {
    	VariableDeclarationFragment fragment= getAST().newVariableDeclarationFragment();
        SimpleName variableName= getAST().newSimpleName(fFieldName);
        fragment.setName(variableName);
        if (fInitializeIn == INITIALIZE_IN_FIELD && tempHasInitializer()){
	        Expression initializer= (Expression)rewrite.createCopy(getTempInitializer());
	        fragment.setInitializer(initializer);
        }
    	FieldDeclaration fieldDeclaration= getAST().newFieldDeclaration(fragment);
    	
    	VariableDeclarationStatement vds= getTempDeclarationStatement();
    	Type type= (Type)rewrite.createCopy(vds.getType());
    	fieldDeclaration.setType(type);
    	fieldDeclaration.setModifiers(getModifiers());
    	return fieldDeclaration;
    }
    
    private int getModifiers() {
    	int flags= fVisibility;
    	if (fDeclareFinal)
    		flags |= Modifier.FINAL;
    	if (fDeclareStatic)	
    		flags |= Modifier.STATIC;
        return flags;
    }
    
    private AST getAST(){
    	return fTempDeclarationNode.getAST();
    }

    private static class LocalTypeAndVariableUsageAnalyzer extends HierarchicalASTVisitor{
    	private final List fLocalTypes= new ArrayList(0);
    	public List getLocalTypeUsageNodes(){
    		return fLocalTypes;
    	}
    	public boolean visitType(Type node) {
	  		ITypeBinding typeBinding= node.resolveBinding();
	  		if (typeBinding != null && typeBinding.isLocal())
	  			fLocalTypes.add(node);
	  		return super.visitType(node);	
		}
		public boolean visitName(Name node) {
			ITypeBinding typeBinding= node.resolveTypeBinding();
			if (typeBinding != null && typeBinding.isLocal())
				fLocalTypes.add(node);
			else {
				IBinding binding= node.resolveBinding();
				if (binding != null && binding.getKind() == IBinding.TYPE && ((ITypeBinding)binding).isLocal())
					fLocalTypes.add(node);
				else if (binding != null && binding.getKind() == IBinding.VARIABLE && ! ((IVariableBinding)binding).isField())
					fLocalTypes.add(node);
			}
            return super.visitName(node);
        }
    }    
}
