/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.ui.codemanipulation;

import java.util.ArrayList;import java.util.List;import org.eclipse.swt.SWT;import org.eclipse.core.resources.IProject;import org.eclipse.core.resources.IResource;import org.eclipse.core.runtime.CoreException;import org.eclipse.core.runtime.IProgressMonitor;import org.eclipse.jdt.core.Flags;import org.eclipse.jdt.core.IBuffer;import org.eclipse.jdt.core.ICompilationUnit;import org.eclipse.jdt.core.IJavaElement;import org.eclipse.jdt.core.IJavaProject;import org.eclipse.jdt.core.IMethod;import org.eclipse.jdt.core.ISourceReference;import org.eclipse.jdt.core.IType;import org.eclipse.jdt.core.ITypeHierarchy;import org.eclipse.jdt.core.JavaModelException;import org.eclipse.jdt.core.Signature;import org.eclipse.jdt.core.search.IJavaSearchConstants;import org.eclipse.jdt.core.search.IJavaSearchScope;import org.eclipse.jdt.core.search.ITypeNameRequestor;import org.eclipse.jdt.core.search.SearchEngine;import org.eclipse.jdt.internal.compiler.ConfigurableOption;import org.eclipse.jdt.internal.formatter.CodeFormatter;import org.eclipse.jdt.internal.ui.JavaPlugin;import org.eclipse.jdt.internal.ui.preferences.CodeFormatterPreferencePage;import org.eclipse.jdt.internal.ui.util.JavaModelUtility;import org.eclipse.jdt.internal.ui.util.TypeRef;import org.eclipse.jdt.internal.ui.util.TypeRefRequestor;

public class StubUtility {

	/**
	 * Generates a stub. Given a template method, a stub with the same signature
	 * will be constructed so it can be added to a type.
	 * @param parenttype The type to which the method will be added to
	 * @param method A method template (method belongs to different type than the parent)
	 * @param imports Imports required by the sub are added to the imports structure 
	 */
	public static String genStub(IType parenttype, IMethod method, IImportsStructure imports) throws JavaModelException {
		IType declaringtype= method.getDeclaringType();	
		StringBuffer buf= new StringBuffer();
		String[] paramTypes= method.getParameterTypes();
		String[] paramNames= method.getParameterNames();	
		int lastParam= paramTypes.length -1;		
		if (!method.isConstructor()) {
			// java doc
			buf.append("\t/**\n\t");
			buf.append(" * @see "); buf.append(declaringtype.getElementName()); buf.append('#');
			buf.append(method.getElementName());
			buf.append('(');
			for (int i= 0; i <= lastParam; i++) {
				buf.append(Signature.getSimpleName(Signature.toString(paramTypes[i])));
				if (i < lastParam) {
					buf.append(", ");
				}
			}
			buf.append(")\n\t");
			buf.append(" */\n\t");
		} else {
			buf.append("\t/**\n\t");
			buf.append(" * Constructor for "); buf.append(parenttype.getElementName()); buf.append("\n\t");
			buf.append(" */\n\t");
		}		
		int flags= method.getFlags();
		if (Flags.isPublic(flags) || (declaringtype.isInterface() && parenttype.isClass())) {
			buf.append("public ");
		} else if (Flags.isProtected(flags)) {
			buf.append("protected ");
		}
		if (Flags.isSynchronized(flags)) {
			buf.append("synchronized ");
		}		
		if (Flags.isVolatile(flags)) {
			buf.append("volatile ");
		}
		if (Flags.isStrictfp(flags)) {
			buf.append("strictfp ");
		}
			
		if (!method.isConstructor()) {
			String retTypeSig= method.getReturnType();
			String retTypeFrm= Signature.toString(retTypeSig);
			if (!isBuiltInType(retTypeSig)) {
				resolveAndAdd(retTypeSig, declaringtype, imports);
			}
			buf.append(Signature.getSimpleName(retTypeFrm));
			buf.append(' ');
			buf.append(method.getElementName());
		} else {
			buf.append(parenttype.getElementName());
		}
		buf.append('(');
		for (int i= 0; i <= lastParam; i++) {
			String paramTypeSig= paramTypes[i];
			String paramTypeFrm= Signature.toString(paramTypeSig);
			if (!isBuiltInType(paramTypeSig)) {
				resolveAndAdd(paramTypeSig, declaringtype, imports);
			}
			buf.append(Signature.getSimpleName(paramTypeFrm));
			buf.append(' ');
			buf.append(paramNames[i]);
			if (i < lastParam) {
				buf.append(", ");
			}
		}
		buf.append(')');
		String[] excTypes= method.getExceptionTypes();
		int lastExc= excTypes.length - 1;
		if (lastExc >= 0) {
			buf.append(" throws ");
			for (int i= 0; i <= lastExc; i++) {
				String excTypeSig= excTypes[i];
				String excTypeFrm= Signature.toString(excTypeSig);
				resolveAndAdd(excTypeSig, declaringtype, imports);
				buf.append(Signature.getSimpleName(excTypeFrm));
				if (i < lastExc) {
					buf.append(", ");
				}
			}
		}
		if (parenttype.isInterface()) {
			buf.append(";\n\n");
		} else {
			buf.append(" {\n\t");
			if (Flags.isAbstract(method.getFlags()) || declaringtype.isInterface()) {
				String retTypeSig= method.getReturnType();
				if (retTypeSig != null && !retTypeSig.equals(Signature.SIG_VOID)) {
					buf.append('\t');
					if (!isBuiltInType(retTypeSig) || Signature.getArrayCount(retTypeSig) > 0) {
						buf.append("return null;\n\t");
					} else if (retTypeSig.equals(Signature.SIG_BOOLEAN)) {
						buf.append("return false;\n\t");
					} else {
						buf.append("return 0;\n\t");
					}
				}
			} else {
				buf.append('\t');
				if (!method.isConstructor()) {
					if (!Signature.SIG_VOID.equals(method.getReturnType())) {
						buf.append("return ");
					}
					buf.append("super.");
					buf.append(method.getElementName());
				} else {
					buf.append("super");
				}
				buf.append('(');			
				for (int i= 0; i <= lastParam; i++) {
					buf.append(paramNames[i]);
					if (i < lastParam) {
						buf.append(", ");
					}
				}
				buf.append(");\n\t");
			}
			buf.append("}\n\n");			
		}
		return buf.toString();
	}

	private static boolean isBuiltInType(String typeName) {
		char first= Signature.getElementType(typeName).charAt(0);
		return (first != Signature.C_RESOLVED && first != Signature.C_UNRESOLVED);
	}

	private static void resolveAndAdd(String refTypeSig, IType declaringType, IImportsStructure imports) throws JavaModelException {
		String resolvedTypeName= getResolvedTypeName(refTypeSig, declaringType);
		if (resolvedTypeName != null) {
			imports.addImport(resolvedTypeName);		
		}
	}

	/**
	 * Finds a method in a type
	 * This searches for a method with the same name and signature. Parameter types are only
	 * compared by the simple name, no resolving for the fully qualified type name is done
	 * @return The first found method or null, if nothing found
	 */
	public static IMethod findMethod(IMethod method, IType type) throws JavaModelException {
		return findMethod(method.getElementName(), method.getParameterTypes(), method.isConstructor(), type.getMethods());
	}

	/**
	 * Finds a method in a type
	 * This searches for a method with the same name and signature. Parameter types are only
	 * compared by the simple name, no resolving for the fully qualified type name is done
	 * @return The first found method or null, if nothing found
	 */
	public static IMethod findMethod(String name, String[] paramTypes, boolean isConstructor, IType type) throws JavaModelException {
		return findMethod(name, paramTypes, isConstructor, type.getMethods());
	}

	/**
	 * Finds a method by name
	 * This searches for a method with a name and signature. Parameter types are only
	 * compared by the simple name, no resolving for the fully qualified type name is done
	 * @param name The name of the method to find
	 * @param paramTypes The parameters of the method to find
	 * @param isConstructor If the method is a constructor
	 * @param methods The methods to search in
	 * @return The found method or null, if nothing found
	 */
	public static IMethod findMethod(String name, String[] paramTypes, boolean isConstructor, IMethod[] methods) throws JavaModelException {
		for (int i= methods.length - 1; i >= 0; i--) {
			IMethod curr= methods[i];
			if (name.equals(curr.getElementName())) {
				if (isConstructor == curr.isConstructor()) {
					if (compareParamTypes(paramTypes, curr.getParameterTypes())) {
						return curr;
					}
				}
			}
		}
		return null;
	}

	/**
	 * Finds a method in a list of methods
	 * This searches for a method with the same name and signature. Parameter types are only
	 * compared by the simple name, no resolving for the fully qualified type name is done
	 * @return The found method or null, if nothing found
	 */
	public static IMethod findMethod(IMethod method, List allMethods) throws JavaModelException {
		String name= method.getElementName();
		String[] paramTypes= method.getParameterTypes();

		for (int i= allMethods.size() - 1; i >= 0; i--) {
			IMethod curr= (IMethod) allMethods.get(i);
			if (name.equals(curr.getElementName())) {
				if (method.isConstructor() == curr.isConstructor()) {
					if (compareParamTypes(paramTypes, curr.getParameterTypes())) {
						return curr;
					}
				}
			}
		}
		return null;
	}

	/**
	 * Finds a method in an array of methods
	 * This searches for a method with the same name and signature. Parameter types are only
	 * compared by the simple name, no resolving for the fully qualified type name is done
	 * @return The first found method or null, if nothing found
	 */
	public static IMethod findMethod(IMethod method, IMethod[] methods) throws JavaModelException {
		return findMethod(method.getElementName(), method.getParameterTypes(), method.isConstructor(), methods);
	}

	/**
	 * Finds a method in a type's hierarchy
	 * This searches for a method with the same name and signature. Parameter types are only
	 * compared by the simple name, no resolving for the fully qualified type name is done
	 * The input type of the hierarchy is not searched for the method
	 * @return The first found method or null, if nothing found
	 */
	public static IMethod findInHierarchy(ITypeHierarchy hierarchy, IMethod method) throws JavaModelException {
		IType curr= hierarchy.getSuperclass(hierarchy.getType());
		while (curr != null) {
			IMethod found= StubUtility.findMethod(method, curr);
			if (found != null) {
				return found;
			}
			curr= hierarchy.getSuperclass(curr);
		}
		return null;
	}

	/*
	 * Compare two parameter signatures
	 */
	public static boolean compareParamTypes(String[] paramTypes1, String[] paramTypes2) {
		if (paramTypes1.length == paramTypes2.length) {
			int i= 0;
			while (i < paramTypes1.length) {
				String t1= Signature.getSimpleName(Signature.toString(paramTypes1[i]));
				String t2= Signature.getSimpleName(Signature.toString(paramTypes2[i]));
				if (!t1.equals(t2)) {
					return false;
				}
				i++;
			}
			return true;
		}
		return false;
	}

	/**
	 * Creates needed constructors for a type
	 * @param type The type to create constructors for
	 * @param supertype The type's super type
	 * @param newMethods The resulting source for the created constructors (List of String)
	 * @param imports Type names for input declarations required (for example 'java.util.Vector')
	 */
	public static void evalConstructors(IType type, IType supertype, List newMethods, IImportsStructure imports) throws JavaModelException {
		IMethod[] methods= supertype.getMethods();
		String stypeName= supertype.getElementName();
		for (int i= 0; i < methods.length; i++) {
			IMethod curr= methods[i];
			if (curr.isConstructor() && JavaModelUtility.isVisible(type.getPackageFragment(), curr.getFlags(), supertype.getPackageFragment())) {
				String newStub= genStub(type, methods[i], imports);
				newMethods.add(newStub);
			}
		}
	}

	/**
	 * Searches for unimplemented methods of a type
	 * @param newMethods The source for the created methods (Vector of String)
	 * @param imports Type names for input declarations required (for example 'java.util.Vector')
	 */
	public static void evalUnimplementedMethods(IType type, ITypeHierarchy hierarchy, List newMethods, IImportsStructure imports) throws JavaModelException {
		List allMethods= new ArrayList();

		IMethod[] typeMethods= type.getMethods();
		for (int i= 0; i < typeMethods.length; i++) {
			IMethod curr= typeMethods[i];
			if (!curr.isConstructor() && !Flags.isStatic(curr.getFlags())) {
				allMethods.add(curr);
			}
		}

		IType[] superTypes= hierarchy.getAllSuperclasses(type);
		for (int i= 0; i < superTypes.length; i++) {
			IMethod[] methods= superTypes[i].getMethods();
			for (int k= 0; k < methods.length; k++) {
				IMethod curr= methods[k];
				if (!curr.isConstructor() && !Flags.isStatic(curr.getFlags())) {
					if (findMethod(curr, allMethods) == null) {
						allMethods.add(curr);
					}
				}
			}
		}

		for (int i= allMethods.size() - 1; i >= 0; i--) {
			IMethod curr= (IMethod) allMethods.get(i);
			if (Flags.isAbstract(curr.getFlags()) && !type.equals(curr.getDeclaringType())) {
				String newStub= genStub(type, curr, imports);
				newMethods.add(newStub);
			}
		}

		IType[] superInterfaces= hierarchy.getAllSuperInterfaces(type);
		for (int i= 0; i < superInterfaces.length; i++) {
			IMethod[] methods= superInterfaces[i].getMethods();
			for (int k= 0; k < methods.length; k++) {
				IMethod curr= methods[k];

				// binary interfaces can contain static initializers (variable intializations)
				// 1G4CKUS
				if (!Flags.isStatic(curr.getFlags())) {
					if (findMethod(curr, allMethods) == null) {
						String newStub= genStub(type, curr, imports);
						newMethods.add(newStub);
						allMethods.add(curr);
					}
				}
			}
		}
	}


	/**
	 * Resolves a type name in the context of the declaring type
	 * @param refTypeSig the type name in signature notation (for example 'QVector')
	 *                   this can also be an array type, but dimensions will be ignored.
	 * @param declaringType the context for resolving (type where the reference was made in)
	 * @return returns the fully qualified type name or build-in-type name. 
	 *  			if a unresoved type couldn't be resolved null is returned
	 */
	public static String getResolvedTypeName(String refTypeSig, IType declaringType) throws JavaModelException {
		int arrayCount= Signature.getArrayCount(refTypeSig);
		char type= refTypeSig.charAt(arrayCount);
		if (type == Signature.C_UNRESOLVED) {
			int semi= refTypeSig.indexOf(Signature.C_SEMICOLON, arrayCount + 1);
			if (semi == -1) {
				throw new IllegalArgumentException();
			}
			String name= refTypeSig.substring(arrayCount + 1, semi);				
			
			String[][] resolvedNames= declaringType.resolveType(name);
			if (resolvedNames != null && resolvedNames.length > 0) {
				return JavaModelUtility.concatenateName(resolvedNames[0][0], resolvedNames[0][1]);
			}
			return null;
		} else {
			return Signature.toString(refTypeSig.substring(arrayCount));
		}
	}

	/**
	 * Finds a type by the simple name
	 */
	public static IType[] findAllTypes(String simpleTypeName, IJavaProject jproject, IProgressMonitor monitor) throws JavaModelException, CoreException {
		SearchEngine searchEngine= new SearchEngine();
		IProject project= jproject.getProject();
		IJavaSearchScope searchScope= SearchEngine.createJavaSearchScope(new IResource[] { project });

		ArrayList typeRefsFound= new ArrayList(10);
		ITypeNameRequestor requestor= new TypeRefRequestor(typeRefsFound);

		searchEngine.searchAllTypeNames(
			project.getWorkspace(), 
			null, 
			simpleTypeName.toCharArray(), 
			IJavaSearchConstants.EXACT_MATCH, 
			IJavaSearchConstants.CASE_SENSITIVE, 
			IJavaSearchConstants.TYPE, 
			searchScope, 
			requestor, 
			IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH, 
			monitor);
			
		int nTypesFound= typeRefsFound.size();
		IType[] res= new IType[nTypesFound];
		for (int i= 0; i < nTypesFound; i++) {
			TypeRef ref= (TypeRef) typeRefsFound.get(i);
			res[i]= ref.resolveType(searchScope);
		}
		return res;
	}

	/**
	 * Examines a string and returns the first line delimiter found
	 */
	public static String getLineDelimiterUsed(IJavaElement elem) throws JavaModelException {
		ICompilationUnit cu= (ICompilationUnit)JavaModelUtility.getParent(elem, IJavaElement.COMPILATION_UNIT);
		if (cu != null) {
			IBuffer buf= cu.getBuffer();
			int length= buf.getLength();
			for (int i= 0; i < length; i++) {
				char ch= buf.getChar(i);
				if (ch == SWT.CR) {
					if (i + 1 < length) {
						if (buf.getChar(i + 1) == SWT.LF) {
							return "\r\n";
						}
					}
					return "\r";
				} else if (ch == SWT.LF) {
					return "\n";
				}
			}
		}
		return System.getProperty("line.separator", "\n");
	}

	/**
	 * Examines a string and returns the indention used
	 */	
	public static int getIndentUsed(IJavaElement elem) throws JavaModelException {
		if (elem instanceof ISourceReference) {
			int tabWidth= CodeFormatterPreferencePage.getTabSize();
			ICompilationUnit cu= (ICompilationUnit)JavaModelUtility.getParent(elem, IJavaElement.COMPILATION_UNIT);
			if (cu != null) {
				IBuffer buf= cu.getBuffer();
				int i= ((ISourceReference)elem).getSourceRange().getOffset();
				int result= 0;
				int blanks= 0;			
				while (i > 0) {
					i--;
					char ch= buf.getChar(i);
					switch (ch) {
						case '\t':
							result++;
							blanks= 0;
							break;
						case	' ':
							blanks++;
							if (blanks == tabWidth) {
								result++;
								blanks= 0;
							}
							break;
						default:
							return result;
					}
				}
			}
		}
		return 0;
	}
	
	public static String codeFormat(String sourceString, int initialIndentationLevel, String lineDelim) {
		// code formatter does not offer all options we need
		
		ConfigurableOption[] options= JavaPlugin.getDefault().getCodeFormatterOptions();
		CodeFormatter formatter= new CodeFormatter(options);
		formatter.options.setLineSeparator(lineDelim);
		formatter.setInitialIndentationLevel(initialIndentationLevel);
		return formatter.formatSourceString(sourceString) + lineDelim;
	}		

}
