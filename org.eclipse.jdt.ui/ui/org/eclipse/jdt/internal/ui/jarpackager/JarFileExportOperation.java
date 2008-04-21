/*******************************************************************************
 * Copyright (c) 2000, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Matt Chapman, mpchapman@gmail.com - 89977 Make JDT .java agnostic
 *     Ferenc Hechler, ferenc_hechler@users.sourceforge.net - 83258 [jar exporter] Deploy java application as executable jar
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.jarpackager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.eclipse.core.filesystem.EFS;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;

import org.eclipse.swt.widgets.Shell;

import org.eclipse.jface.operation.ModalContext;

import org.eclipse.ui.actions.WorkspaceModifyOperation;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaModelMarker;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IRegion;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.util.IClassFileReader;
import org.eclipse.jdt.core.util.ISourceAttribute;

import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.jdt.internal.corext.util.Messages;
import org.eclipse.jdt.internal.corext.util.Resources;

import org.eclipse.jdt.ui.StandardJavaElementContentProvider;
import org.eclipse.jdt.ui.jarpackager.IJarBuilder;
import org.eclipse.jdt.ui.jarpackager.IJarDescriptionWriter;
import org.eclipse.jdt.ui.jarpackager.IJarExportRunnable;
import org.eclipse.jdt.ui.jarpackager.JarPackageData;

import org.eclipse.jdt.internal.ui.IJavaStatusConstants;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.refactoring.RefactoringSaveHelper;
import org.eclipse.jdt.internal.ui.util.BusyIndicatorRunnableContext;
import org.eclipse.jdt.internal.ui.viewsupport.BasicElementLabels;

/**
 * Operation for exporting a resource and its children to a new  JAR file.
 */
public class JarFileExportOperation extends WorkspaceModifyOperation implements IJarExportRunnable {

	private static class MessageMultiStatus extends MultiStatus {
		MessageMultiStatus(String pluginId, int code, String message, Throwable exception) {
			super(pluginId, code, message, exception);
		}
		/*
		 * allows to change the message
		 */
		protected void setMessage(String message) {
			super.setMessage(message);
		}
	}

	private IJarBuilder fJarBuilder;
	private JarPackageData fJarPackage;
	private JarPackageData[] fJarPackages;
	private Shell fParentShell;
	private Map fJavaNameToClassFilesMap;
	private IContainer fClassFilesMapContainer;
	private Set fExportedClassContainers;
	private MessageMultiStatus fStatus;
	private StandardJavaElementContentProvider fJavaElementContentProvider;
	private boolean fFilesSaved;
	
	/**
	 * Creates an instance of this class.
	 *
	 * @param	jarPackage	the JAR package specification
	 * @param	parent	the parent for the dialog,
	 * 			or <code>null</code> if no dialog should be presented
	 */
	public JarFileExportOperation(JarPackageData jarPackage, Shell parent) {
		this(new JarPackageData[] {jarPackage}, parent);
	}

	/**
	 * Creates an instance of this class.
	 *
	 * @param	jarPackages		an array with JAR package data objects
	 * @param	parent			the parent for the dialog,
	 * 			or <code>null</code> if no dialog should be presented
	 */
	public JarFileExportOperation(JarPackageData[] jarPackages, Shell parent) {
		this(parent);
		fJarPackages= jarPackages;
	}

	private JarFileExportOperation(Shell parent) {
		fParentShell= parent;
		fStatus= new MessageMultiStatus(JavaPlugin.getPluginId(), IStatus.OK, "", null); //$NON-NLS-1$
		fJavaElementContentProvider= new StandardJavaElementContentProvider();
	}

	private void addToStatus(CoreException ex) {
		IStatus status= ex.getStatus();
		String message= ex.getLocalizedMessage();
		if (message == null || message.length() < 1) {
			message= JarPackagerMessages.JarFileExportOperation_coreErrorDuringExport;
			status= new Status(status.getSeverity(), status.getPlugin(), status.getCode(), message, ex);
		}
		fStatus.add(status);
	}

	/**
	 * Adds a new info to the list with the passed information.
	 * Normally the export operation continues after a warning.
	 * @param	message		the message
	 * @param	error 	the throwable that caused the warning, or <code>null</code>
	 */
	protected void addInfo(String message, Throwable error) {
		fStatus.add(new Status(IStatus.INFO, JavaPlugin.getPluginId(), IJavaStatusConstants.INTERNAL_ERROR, message, error));
	}

	/**
	 * Adds a new warning to the list with the passed information.
	 * Normally the export operation continues after a warning.
	 * @param	message		the message
	 * @param	error	the throwable that caused the warning, or <code>null</code>
	 */
	private void addWarning(String message, Throwable error) {
		fStatus.add(new Status(IStatus.WARNING, JavaPlugin.getPluginId(), IJavaStatusConstants.INTERNAL_ERROR, message, error));
	}
	
	/**
	 * Adds a new error to the list with the passed information.
	 * Normally an error terminates the export operation.
	 * @param	message		the message
	 * @param	error 	the throwable that caused the error, or <code>null</code>
	 */
	private void addError(String message, Throwable error) {
		fStatus.add(new Status(IStatus.ERROR, JavaPlugin.getPluginId(), IJavaStatusConstants.INTERNAL_ERROR, message, error));
	}

	/**
	 * Answers the number of file resources specified by the JAR package.
	 *
	 * @return int
	 */
	private int countSelectedElements() {
		Set enclosingJavaProjects= new HashSet(10);
		int count= 0;
		
		int n= fJarPackage.getElements().length;
		for (int i= 0; i < n; i++) {
			Object element= fJarPackage.getElements()[i];
			
			IJavaProject javaProject= getEnclosingJavaProject(element);
			if (javaProject != null)
				enclosingJavaProjects.add(javaProject);
			
			IResource resource= null;
			if (element instanceof IJavaElement) {
				IJavaElement je= (IJavaElement)element;
				try {
					resource= je.getUnderlyingResource();
				} catch (JavaModelException ex) {
					continue;
				}
				
				if (resource == null) {
					if (element instanceof IPackageFragmentRoot) {
						IPackageFragmentRoot root= (IPackageFragmentRoot) element;
						if (root.isArchive()) {
							try {
								ZipFile file= JarPackagerUtil.getArchiveFile(root.getPath());
								if (file != null)
									count+= file.size();
							} catch (CoreException e) {
								JavaPlugin.log(e);
							}
						}
					}
					continue;
				}
			}
			else
				resource= (IResource)element;
			if (resource != null) {
				if (resource.getType() == IResource.FILE)
					count++;
				else
					count+= getTotalChildCount((IContainer) resource);
			}
		}
		
		if (fJarPackage.areOutputFoldersExported()) {
			if (!fJarPackage.areJavaFilesExported())
				count= 0;
			Iterator iter= enclosingJavaProjects.iterator();
			while (iter.hasNext()) {
				IJavaProject javaProject= (IJavaProject)iter.next();
				IContainer[] outputContainers;
				try {
					outputContainers= getOutputContainers(javaProject);
				} catch (CoreException ex) {
					addToStatus(ex);
					continue;
				}
				for (int i= 0; i < outputContainers.length; i++)
					count += getTotalChildCount(outputContainers[i]);

			}
		}
		
		return count;
	}
	
	private int getTotalChildCount(IContainer container) {
		IResource[] members;
		try {
			members= container.members();
		} catch (CoreException ex) {
			return 0;
		}
		int count= 0;
		for (int i= 0; i < members.length; i++) {
			if (members[i].getType() == IResource.FILE)
				count++;
			else
				count += getTotalChildCount((IContainer)members[i]);
		}
		return count;
	}

	/**
	 * Exports the passed resource to the JAR file
	 *
	 * @param element the resource or JavaElement to export
	 * @param progressMonitor the progress monitor
	 * @throws InterruptedException thrown on cancel
	 */
	private void exportElement(Object element, IProgressMonitor progressMonitor) throws InterruptedException {
		int leadSegmentsToRemove= 1;
		IPackageFragmentRoot pkgRoot= null;
		boolean isInJavaProject= false;
		IResource resource= null;
		ITypeRoot typeRootElement= null;
		IJavaProject jProject= null;
		if (element instanceof IJavaElement) {
			isInJavaProject= true;
			IJavaElement je= (IJavaElement)element;
			if (!(je instanceof ITypeRoot)) {
				exportJavaElement(progressMonitor, je);
				return;
			}
			typeRootElement= (ITypeRoot) je;
			jProject= typeRootElement.getJavaProject();
			pkgRoot= JavaModelUtil.getPackageFragmentRoot(je);
			resource= typeRootElement.getResource();
		} else
			resource= (IResource)element;

		if (!resource.isAccessible()) {
			addWarning(Messages.format(JarPackagerMessages.JarFileExportOperation_resourceNotFound, resource.getFullPath()), null);
			return;
		}

		if (resource.getType() == IResource.FILE) {
			if (!isInJavaProject) {
				// check if it's a Java resource
				try {
					isInJavaProject= resource.getProject().hasNature(JavaCore.NATURE_ID);
				} catch (CoreException ex) {
					addWarning(Messages.format(JarPackagerMessages.JarFileExportOperation_projectNatureNotDeterminable, resource.getFullPath()), ex);
					return;
				}
				if (isInJavaProject) {
					IJavaElement je= JavaCore.create(resource);
					if (je instanceof ITypeRoot && je.exists()) {
						exportElement(je, progressMonitor);
						return;
					}

					jProject= JavaCore.create(resource.getProject());
					try {
						IPackageFragment pkgFragment= jProject.findPackageFragment(resource.getFullPath().removeLastSegments(1));
						if (pkgFragment != null)
							pkgRoot= JavaModelUtil.getPackageFragmentRoot(pkgFragment);
						else
							pkgRoot= findPackageFragmentRoot(jProject, resource.getFullPath().removeLastSegments(1));
					} catch (JavaModelException ex) {
						addWarning(Messages.format(JarPackagerMessages.JarFileExportOperation_javaPackageNotDeterminable, resource.getFullPath()), ex);
						return;
					}
				}
			}
			
			if (pkgRoot != null && jProject != null) {
				leadSegmentsToRemove= pkgRoot.getPath().segmentCount();
				boolean isOnBuildPath;
				isOnBuildPath= jProject.isOnClasspath(resource);
				if (!isOnBuildPath || (mustUseSourceFolderHierarchy() && !pkgRoot.getElementName().equals(IPackageFragmentRoot.DEFAULT_PACKAGEROOT_PATH)))
					leadSegmentsToRemove--;
			}
			
			IPath destinationPath= resource.getFullPath().removeFirstSegments(leadSegmentsToRemove);
			
			if (typeRootElement != null) {
				exportClassFiles(progressMonitor, typeRootElement, destinationPath);
			}
			
			exportResource(progressMonitor, pkgRoot, isInJavaProject, resource, destinationPath);

			progressMonitor.worked(1);
			ModalContext.checkCanceled(progressMonitor);

		} else
			exportContainer(progressMonitor, (IContainer)resource);
	}

	private void exportJavaElement(IProgressMonitor progressMonitor, IJavaElement je) throws InterruptedException {
		if (je.getElementType() == IJavaElement.PACKAGE_FRAGMENT_ROOT && ((IPackageFragmentRoot) je).isArchive()) {
			IPackageFragmentRoot root= (IPackageFragmentRoot) je;
			ZipFile jarFile= null;
			try {
				jarFile= JarPackagerUtil.getArchiveFile(root.getPath());
				fJarBuilder.writeArchive(jarFile, progressMonitor);
			} catch (CoreException e) {
				addWarning(Messages.format(JarPackagerMessages.JarFileExportOperation_OpenZipFileError_message, new Object[] { root.getElementName(), e.getLocalizedMessage() }), e);
			} finally {
				try {
					if (jarFile != null) {
						jarFile.close();
					}
				} catch (IOException e) {
					addWarning(Messages.format(JarPackagerMessages.JarFileExportOperation_CloseZipFileError_message, new Object[] { root.getElementName(), e.getLocalizedMessage() }), e);
				}
			}
			return;
		}

		Object[] children= fJavaElementContentProvider.getChildren(je);
		for (int i= 0; i < children.length; i++)
			exportElement(children[i], progressMonitor);
	}

	private void exportResource(IProgressMonitor progressMonitor, IResource resource, int leadingSegmentsToRemove) throws InterruptedException {
		if (resource instanceof IContainer) {
			IContainer container= (IContainer)resource;
			IResource[] children;
			try {
				children= container.members();
			} catch (CoreException e) {
				// this should never happen because an #isAccessible check is done before #members is invoked
				addWarning(Messages.format(JarPackagerMessages.JarFileExportOperation_errorDuringExport, container.getFullPath()), e);
				return;
			}
			for (int i= 0; i < children.length; i++)
				exportResource(progressMonitor, children[i], leadingSegmentsToRemove);
		} else if (resource instanceof IFile) {
			try {
				IPath destinationPath= resource.getFullPath().removeFirstSegments(leadingSegmentsToRemove);
				progressMonitor.subTask(Messages.format(JarPackagerMessages.JarFileExportOperation_exporting, BasicElementLabels.getPathLabel(destinationPath, false)));
				fJarBuilder.writeFile((IFile)resource, destinationPath);
			} catch (CoreException ex) {
				Throwable realEx= ex.getStatus().getException();
				if (realEx instanceof ZipException && realEx.getMessage() != null && realEx.getMessage().startsWith("duplicate entry:")) //$NON-NLS-1$
					addWarning(ex.getMessage(), realEx);
				else
					addToStatus(ex);
			} finally {
				progressMonitor.worked(1);
				ModalContext.checkCanceled(progressMonitor);
			}
		}
	}

	private void exportContainer(IProgressMonitor progressMonitor, IContainer container) throws InterruptedException {
		if (container.getType() == IResource.FOLDER && isOutputFolder((IFolder)container))
			return;
		
		IResource[] children= null;
		try {
			children= container.members();
		} catch (CoreException exception) {
			// this should never happen because an #isAccessible check is done before #members is invoked
			addWarning(Messages.format(JarPackagerMessages.JarFileExportOperation_errorDuringExport, container.getFullPath()), exception);
		}
		if (children != null) {
			for (int i= 0; i < children.length; i++)
				exportElement(children[i], progressMonitor);
		}
	}

	private IPackageFragmentRoot findPackageFragmentRoot(IJavaProject jProject, IPath path) throws JavaModelException {
		if (jProject == null || path == null || path.segmentCount() <= 0)
			return null;
		IPackageFragmentRoot pkgRoot= jProject.findPackageFragmentRoot(path);
		if (pkgRoot != null)
			return pkgRoot;
		else
			return findPackageFragmentRoot(jProject, path.removeLastSegments(1));
	}

	private void exportResource(IProgressMonitor progressMonitor, IPackageFragmentRoot pkgRoot, boolean isInJavaProject, IResource resource, IPath destinationPath) {

		// Handle case where META-INF/MANIFEST.MF is part of the exported files
		if (fJarPackage.areClassFilesExported() && destinationPath.toString().equals("META-INF/MANIFEST.MF")) {//$NON-NLS-1$
			if (fJarPackage.isManifestGenerated())
				addWarning(Messages.format(JarPackagerMessages.JarFileExportOperation_didNotAddManifestToJar, resource.getFullPath()), null);
			return;
		}

		boolean isNonJavaResource= !isInJavaProject || pkgRoot == null;
		boolean isInClassFolder= false;
		try {
			isInClassFolder= pkgRoot != null && !pkgRoot.isArchive() && pkgRoot.getKind() == IPackageFragmentRoot.K_BINARY;
		} catch (JavaModelException ex) {
			addWarning(Messages.format(JarPackagerMessages.JarFileExportOperation_cantGetRootKind, resource.getFullPath()), ex);
		}
		if ((fJarPackage.areClassFilesExported() &&
					((isNonJavaResource || (pkgRoot != null && !isJavaFile(resource) && !isClassFile(resource)))
					|| isInClassFolder && isClassFile(resource)))
			|| (fJarPackage.areJavaFilesExported() && (isNonJavaResource || (pkgRoot != null && !isClassFile(resource)) || (isInClassFolder && isClassFile(resource) && !fJarPackage.areClassFilesExported())))) {
			try {
				progressMonitor.subTask(Messages.format(JarPackagerMessages.JarFileExportOperation_exporting, destinationPath.toString()));
				fJarBuilder.writeFile((IFile)resource, destinationPath);
			} catch (CoreException ex) {
				Throwable realEx= ex.getStatus().getException();
				if (realEx instanceof ZipException && realEx.getMessage() != null && realEx.getMessage().startsWith("duplicate entry:")) //$NON-NLS-1$
					addWarning(ex.getMessage(), realEx);
				else
					addToStatus(ex);
			}
		}
	}

	private boolean isOutputFolder(IFolder folder) {
		try {
			IJavaProject javaProject= JavaCore.create(folder.getProject());
			IPath outputFolderPath= javaProject.getOutputLocation();
			return folder.getFullPath().equals(outputFolderPath);
		} catch (JavaModelException ex) {
			return false;
		}
	}

	private void exportClassFiles(IProgressMonitor progressMonitor, ITypeRoot typeRootElement, IPath destinationPath) {
		if (fJarPackage.areClassFilesExported()) {
			try {
				if (!typeRootElement.exists())
					return;

				// find corresponding file(s) on classpath and export
				Iterator iter= filesOnClasspath(typeRootElement, destinationPath, progressMonitor);
				IPath baseDestinationPath= destinationPath.removeLastSegments(1);
				while (iter.hasNext()) {
					IFile file= (IFile)iter.next();
					IPath classFilePath= baseDestinationPath.append(file.getName());
					progressMonitor.subTask(Messages.format(JarPackagerMessages.JarFileExportOperation_exporting, BasicElementLabels.getPathLabel(classFilePath, false)));
					fJarBuilder.writeFile(file, classFilePath);
				}
			} catch (CoreException ex) {
				addToStatus(ex);
			}
		}
	}

	/**
	 * Exports the resources as specified by the JAR package.
	 * @param progressMonitor the progress monitor
	 * @throws InterruptedException thrown when cancelled
	 */
	private void exportSelectedElements(IProgressMonitor progressMonitor) throws InterruptedException {
		fExportedClassContainers= new HashSet(10);
		Set enclosingJavaProjects= new HashSet(10);
		int n= fJarPackage.getElements().length;
		for (int i= 0; i < n; i++) {
			Object element= fJarPackage.getElements()[i];
			exportElement(element, progressMonitor);
			if (fJarPackage.areOutputFoldersExported()) {
				IJavaProject javaProject= getEnclosingJavaProject(element);
				if (javaProject != null)
					enclosingJavaProjects.add(javaProject);
			}
		}
		if (fJarPackage.areOutputFoldersExported())
			exportOutputFolders(progressMonitor, enclosingJavaProjects);
	}
	
	private IJavaProject getEnclosingJavaProject(Object element) {
		if (element instanceof IJavaElement) {
			return ((IJavaElement)element).getJavaProject();
		} else if (element instanceof IResource) {
			IProject project= ((IResource)element).getProject();
			try {
				if (project.hasNature(JavaCore.NATURE_ID))
					return JavaCore.create(project);
			} catch (CoreException ex) {
				addWarning(Messages.format(JarPackagerMessages.JarFileExportOperation_projectNatureNotDeterminable, project.getFullPath()), ex);
			}
		}
		return null;
	}
	
	private void exportOutputFolders(IProgressMonitor progressMonitor, Set javaProjects) throws InterruptedException {
		if (javaProjects == null)
			return;
		
		Iterator iter= javaProjects.iterator();
		while (iter.hasNext()) {
			IJavaProject javaProject= (IJavaProject)iter.next();
			IContainer[] outputContainers;
			try {
				outputContainers= getOutputContainers(javaProject);
			} catch (CoreException ex) {
				addToStatus(ex);
				continue;
			}
			for (int i= 0; i < outputContainers.length; i++)
				exportResource(progressMonitor, outputContainers[i], outputContainers[i].getFullPath().segmentCount());

		}
	}
	
	private IContainer[] getOutputContainers(IJavaProject javaProject) throws CoreException {
		Set outputPaths= new HashSet();
		boolean includeDefaultOutputPath= false;
		IPackageFragmentRoot[] roots= javaProject.getPackageFragmentRoots();
		for (int i= 0; i < roots.length; i++) {
			if (roots[i] != null) {
				IClasspathEntry cpEntry= roots[i].getRawClasspathEntry();
				if (cpEntry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
					IPath location= cpEntry.getOutputLocation();
					if (location != null)
						outputPaths.add(location);
					else
						includeDefaultOutputPath= true;
				}
			}
		}
		
		if (includeDefaultOutputPath) {
			// Use default output location
			outputPaths.add(javaProject.getOutputLocation());
		}
		
		// Convert paths to containers
		Set outputContainers= new HashSet(outputPaths.size());
		Iterator iter= outputPaths.iterator();
		while (iter.hasNext()) {
			IPath path= (IPath)iter.next();
			if (javaProject.getProject().getFullPath().equals(path))
				outputContainers.add(javaProject.getProject());
			else {
				IFolder outputFolder= createFolderHandle(path);
				if (outputFolder == null || !outputFolder.isAccessible()) {
					String msg= JarPackagerMessages.JarFileExportOperation_outputContainerNotAccessible;
					addToStatus(new CoreException(new Status(IStatus.ERROR, JavaPlugin.getPluginId(), IJavaStatusConstants.INTERNAL_ERROR, msg, null)));
				} else
					outputContainers.add(outputFolder);
			}
		}
		return (IContainer[])outputContainers.toArray(new IContainer[outputContainers.size()]);
	}
	
	/**
	 * Returns an iterator on a list with files that correspond to the
	 * passed file and that are on the classpath of its project.
	 *
	 * @param	typeRootElement			the class file or compilation unit to evaluate the class files for
	 * @param	pathInJar		the path that the file has in the JAR (i.e. project and source folder segments removed)
	 * @param	progressMonitor			the progressMonitor to use
	 * @return	the iterator over the corresponding classpath files for the given file
	 * @throws CoreException
	 */
	private Iterator filesOnClasspath(ITypeRoot typeRootElement, IPath pathInJar, IProgressMonitor progressMonitor) throws CoreException {
		IFile file= (IFile) typeRootElement.getResource();
		IJavaProject javaProject= typeRootElement.getJavaProject();
		IPackageFragmentRoot pkgRoot= JavaModelUtil.getPackageFragmentRoot(typeRootElement);
		
		// Allow JAR Package to provide its own strategy
		IFile[] classFiles= fJarPackage.findClassfilesFor(file);
		if (classFiles != null)
			return Arrays.asList(classFiles).iterator();

		if (!isJavaFile(file))
			return Collections.EMPTY_LIST.iterator();

		IPath outputPath= null;
		if (pkgRoot != null) {
			IClasspathEntry cpEntry= pkgRoot.getRawClasspathEntry();
			if (cpEntry.getEntryKind() == IClasspathEntry.CPE_SOURCE)
				outputPath= cpEntry.getOutputLocation();
		}
		if (outputPath == null)
			// Use default output location
			outputPath= javaProject.getOutputLocation();
				
		IContainer outputContainer;
		if (javaProject.getProject().getFullPath().equals(outputPath))
			outputContainer= javaProject.getProject();
		else {
			outputContainer= createFolderHandle(outputPath);
			if (outputContainer == null || !outputContainer.isAccessible()) {
				String msg= JarPackagerMessages.JarFileExportOperation_outputContainerNotAccessible;
				throw new CoreException(new Status(IStatus.ERROR, JavaPlugin.getPluginId(), IJavaStatusConstants.INTERNAL_ERROR, msg, null));
			}
		}

		// Java CU - search files with .class ending
		boolean hasErrors= hasCompileErrors(file);
		boolean hasWarnings= hasCompileWarnings(file);
		boolean canBeExported= canBeExported(hasErrors, hasWarnings);
		if (!canBeExported)
			return Collections.EMPTY_LIST.iterator();
		reportPossibleCompileProblems(file, hasErrors, hasWarnings, canBeExported);
		IContainer classContainer= outputContainer;
		if (pathInJar.segmentCount() > 1)
			classContainer= outputContainer.getFolder(pathInJar.removeLastSegments(1));
			
		if (fExportedClassContainers.contains(classContainer))
			return Collections.EMPTY_LIST.iterator();
		
		if (JavaCore.DO_NOT_GENERATE.equals(javaProject.getOption(JavaCore.COMPILER_SOURCE_FILE_ATTR, true))) {
			IRegion region= JavaCore.newRegion();
			region.add(typeRootElement);
			IResource[] generatedResources= JavaCore.getGeneratedResources(region, false);
			if (generatedResources.length > 0)
				return Arrays.asList(generatedResources).iterator();
			// give the old code a last chance
		}
		if (fClassFilesMapContainer == null || !fClassFilesMapContainer.equals(classContainer)) {
			fJavaNameToClassFilesMap= buildJavaToClassMap(classContainer, progressMonitor);
			if (fJavaNameToClassFilesMap == null) {
				// Could not fully build map. fallback is to export whole directory
				String containerName= BasicElementLabels.getPathLabel(classContainer.getFullPath(), false);
				String msg= Messages.format(JarPackagerMessages.JarFileExportOperation_missingSourceFileAttributeExportedAll, containerName);
				addInfo(msg, null);
				fExportedClassContainers.add(classContainer);
				return getClassesIn(classContainer);
			}
			fClassFilesMapContainer= classContainer;
		}
		ArrayList classFileList= (ArrayList)fJavaNameToClassFilesMap.get(file.getName());
		if (classFileList == null || classFileList.isEmpty()) {
			String msg= Messages.format(JarPackagerMessages.JarFileExportOperation_classFileOnClasspathNotAccessible, file.getFullPath());
			throw new CoreException(new Status(IStatus.ERROR, JavaPlugin.getPluginId(), IJavaStatusConstants.INTERNAL_ERROR, msg, null));
		}
		return classFileList.iterator();
	}

	private Iterator getClassesIn(IContainer classContainer) throws CoreException {
		IResource[] resources= classContainer.members();
		List files= new ArrayList(resources.length);
		for (int i= 0; i < resources.length; i++)
			if (resources[i].getType() == IResource.FILE && isClassFile(resources[i]))
				files.add(resources[i]);
		return files.iterator();
	}

	/**
	 * Answers whether the given resource is a Java file.
	 * The resource must be a file whose file name ends with ".java",
	 * or an extension defined as Java source.
	 * 
	 * @param file the file to test
	 * @return a <code>true<code> if the given resource is a Java file
	 */
	private boolean isJavaFile(IResource file) {
		return file != null
			&& file.getType() == IResource.FILE
			&& file.getFileExtension() != null
			&& JavaCore.isJavaLikeFileName(file.getName());
	}

	/**
	 * Answers whether the given resource is a class file.
	 * The resource must be a file whose file name ends with ".class".
	 * 
	 * @param file the file to test
	 * @return a <code>true<code> if the given resource is a class file
	 */
	private boolean isClassFile(IResource file) {
		return file != null
			&& file.getType() == IResource.FILE
			&& file.getFileExtension() != null
			&& file.getFileExtension().equalsIgnoreCase("class"); //$NON-NLS-1$
	}

	/*
	 * Builds and returns a map that has the class files
	 * for each java file in a given directory
	 */
	private Map buildJavaToClassMap(IContainer container, IProgressMonitor monitor) throws CoreException {
		if (container == null || !container.isAccessible())
			return new HashMap(0);
		/*
		 * XXX: Bug 6584: Need a way to get class files for a java file (or CU)
		 */
		IClassFileReader cfReader= null;
		IResource[] members= container.members();
		Map map= new HashMap(members.length);
		for (int i= 0;  i < members.length; i++) {
			if (isClassFile(members[i])) {
				IFile classFile= (IFile)members[i];
				URI location= classFile.getLocationURI();
				if (location != null) {
					InputStream contents= null;
					try {
						contents= EFS.getStore(location).openInputStream(EFS.NONE, monitor);
						cfReader= ToolFactory.createDefaultClassFileReader(contents, IClassFileReader.CLASSFILE_ATTRIBUTES);
					} finally {
						try {
							if (contents != null)
								contents.close();
						} catch (IOException e) {
							throw new CoreException(new Status(IStatus.ERROR, JavaPlugin.getPluginId(), IStatus.ERROR,
								Messages.format(JarPackagerMessages.JarFileExportOperation_errorCannotCloseConnection, Resources.getLocationString(classFile)),
								e));
						}
					}
					if (cfReader != null) {
						ISourceAttribute sourceAttribute= cfReader.getSourceFileAttribute();
						if (sourceAttribute == null) {
							/*
							 * Can't fully build the map because one or more
							 * class file does not contain the name of its
							 * source file.
							 */
							addWarning(Messages.format(
								JarPackagerMessages.JarFileExportOperation_classFileWithoutSourceFileAttribute,
								Resources.getLocationString(classFile)), null);
							return null;
						}
						String javaName= new String(sourceAttribute.getSourceFileName());
						Object classFiles= map.get(javaName);
						if (classFiles == null) {
							classFiles= new ArrayList(3);
							map.put(javaName, classFiles);
						}
						((ArrayList)classFiles).add(classFile);
					}
				}
			}
		}
		return map;
	}

	/**
	 * Creates a folder resource handle for the folder with the given workspace path.
	 *
	 * @param folderPath the path of the folder to create a handle for
	 * @return the new folder resource handle
	 */
	private IFolder createFolderHandle(IPath folderPath) {
		if (folderPath.isValidPath(folderPath.toString()) && folderPath.segmentCount() >= 2)
			return JavaPlugin.getWorkspace().getRoot().getFolder(folderPath);
		else
			return null;
	}

	/**
	 * Returns the status of this operation.
	 * The result is a status object containing individual
	 * status objects.
	 *
	 * @return the status of this operation
	 */
	public IStatus getStatus() {
		String message= null;
		switch (fStatus.getSeverity()) {
			case IStatus.OK:
				message= ""; //$NON-NLS-1$
				break;
			case IStatus.INFO:
				message= JarPackagerMessages.JarFileExportOperation_exportFinishedWithInfo;
				break;
			case IStatus.WARNING:
				message= JarPackagerMessages.JarFileExportOperation_exportFinishedWithWarnings;
				break;
			case IStatus.ERROR:
				if (fJarPackages.length > 1)
					message= JarPackagerMessages.JarFileExportOperation_creationOfSomeJARsFailed;
				else
					message= JarPackagerMessages.JarFileExportOperation_jarCreationFailed;
				break;
			default:
				// defensive code in case new severity is defined
				message= ""; //$NON-NLS-1$
				break;
		}
		fStatus.setMessage(message);
		return fStatus;
	}

	private boolean canBeExported(boolean hasErrors, boolean hasWarnings) {
		return (!hasErrors && !hasWarnings)
			|| (hasErrors && fJarPackage.areErrorsExported())
			|| (hasWarnings && fJarPackage.exportWarnings());
	}

	private void reportPossibleCompileProblems(IFile file, boolean hasErrors, boolean hasWarnings, boolean canBeExported) {
		if (hasErrors) {
			if (canBeExported)
				addWarning(Messages.format(JarPackagerMessages.JarFileExportOperation_exportedWithCompileErrors, file.getFullPath()), null);
			else
				addWarning(Messages.format(JarPackagerMessages.JarFileExportOperation_notExportedDueToCompileErrors, file.getFullPath()), null);
		}
		if (hasWarnings) {
			if (canBeExported)
				addWarning(Messages.format(JarPackagerMessages.JarFileExportOperation_exportedWithCompileWarnings, file.getFullPath()), null);
			else
				addWarning(Messages.format(JarPackagerMessages.JarFileExportOperation_notExportedDueToCompileWarnings, file.getFullPath()), null);
		}
	}

	/**
	 * Exports the resources as specified by the JAR package.
	 * 
	 * @param	progressMonitor	the progress monitor that displays the progress
	 * @throws InvocationTargetException thrown when an ecxeption occurred
	 * @throws InterruptedException thrown when cancelled
	 * @see	#getStatus()
	 */
	protected void execute(IProgressMonitor progressMonitor) throws InvocationTargetException, InterruptedException {
		int count= fJarPackages.length;
		progressMonitor.beginTask("", count); //$NON-NLS-1$
		try {
			for (int i= 0; i < count; i++) {
				SubProgressMonitor subProgressMonitor= new SubProgressMonitor(progressMonitor, 1, SubProgressMonitor.PREPEND_MAIN_LABEL_TO_SUBTASK);
				fJarPackage= fJarPackages[i];
				if (fJarPackage != null)
					singleRun(subProgressMonitor);
			}
		} finally {
			progressMonitor.done();
		}
	}

	private void singleRun(IProgressMonitor progressMonitor) throws InvocationTargetException, InterruptedException {
		try {
			if (!preconditionsOK())
				throw new InvocationTargetException(null, JarPackagerMessages.JarFileExportOperation_jarCreationFailedSeeDetails);
			int totalWork= countSelectedElements();
			if (fJarPackage.areGeneratedFilesExported()
				&& ((!isAutoBuilding() && fJarPackage.isBuildingIfNeeded())
					|| (isAutoBuilding() && fFilesSaved))) {
				int subMonitorTicks= totalWork/10;
				totalWork += subMonitorTicks;
				progressMonitor.beginTask("", totalWork); //$NON-NLS-1$
				SubProgressMonitor subProgressMonitor= new SubProgressMonitor(progressMonitor, subMonitorTicks, SubProgressMonitor.PREPEND_MAIN_LABEL_TO_SUBTASK);
				buildProjects(subProgressMonitor);
			} else
				progressMonitor.beginTask("", totalWork); //$NON-NLS-1$
			
			fJarBuilder = fJarPackage.getJarBuilder();
			fJarBuilder.open(fJarPackage, fParentShell, fStatus);
			
			exportSelectedElements(progressMonitor);
			if (getStatus().getSeverity() != IStatus.ERROR) {
				progressMonitor.subTask(JarPackagerMessages.JarFileExportOperation_savingFiles);
				saveFiles();
			}
		} catch (CoreException ex) {
			addToStatus(ex);
		} finally {
			try {
				if (fJarBuilder != null)
					fJarBuilder.close();
			} catch (CoreException ex) {
				addToStatus(ex);
			}
			progressMonitor.done();
		}
	}
	
	private boolean preconditionsOK() {
		if (!fJarPackage.areGeneratedFilesExported() && !fJarPackage.areJavaFilesExported()) {
			addError(JarPackagerMessages.JarFileExportOperation_noExportTypeChosen, null);
			return false;
		}
		if (fJarPackage.getElements() == null || fJarPackage.getElements().length == 0) {
			addError(JarPackagerMessages.JarFileExportOperation_noResourcesSelected, null);
			return false;
		}
		if (fJarPackage.getAbsoluteJarLocation() == null) {
			addError(JarPackagerMessages.JarFileExportOperation_invalidJarLocation, null);
			return false;
		}
		File targetFile= fJarPackage.getAbsoluteJarLocation().toFile();
		if (targetFile.exists() && !targetFile.canWrite()) {
			addError(JarPackagerMessages.JarFileExportOperation_jarFileExistsAndNotWritable, null);
			return false;
		}
		if (!fJarPackage.isManifestAccessible()) {
			addError(JarPackagerMessages.JarFileExportOperation_manifestDoesNotExist, null);
			return false;
		}
		if (!fJarPackage.isMainClassValid(new BusyIndicatorRunnableContext())) {
			addError(JarPackagerMessages.JarFileExportOperation_invalidMainClass, null);
			return false;
		}
		
		if (fParentShell != null) {
			final boolean[] res= { false };
			fParentShell.getDisplay().syncExec(new Runnable() {
				public void run() {
					RefactoringSaveHelper refactoringSaveHelper= new RefactoringSaveHelper(RefactoringSaveHelper.SAVE_ALL_ALWAYS_ASK);
					res[0]= refactoringSaveHelper.saveEditors(fParentShell);
					fFilesSaved= refactoringSaveHelper.hasFilesSaved();
				}
			});
			if (!res[0]) {
				addError(JarPackagerMessages.JarFileExportOperation_fileUnsaved, null);
				return false;
			}
		}

		return true;
	}

	private void saveFiles() {
		// Save the manifest
		if (fJarPackage.areGeneratedFilesExported() && fJarPackage.isManifestGenerated() && fJarPackage.isManifestSaved()) {
			try {
				saveManifest();
			} catch (CoreException ex) {
				addError(JarPackagerMessages.JarFileExportOperation_errorSavingManifest, ex);
			} catch (IOException ex) {
				addError(JarPackagerMessages.JarFileExportOperation_errorSavingManifest, ex);
			}
		}
		
		// Save the description
		if (fJarPackage.isDescriptionSaved()) {
			try {
				saveDescription();
			} catch (CoreException ex) {
				addError(JarPackagerMessages.JarFileExportOperation_errorSavingDescription, ex);
			} catch (IOException ex) {
				addError(JarPackagerMessages.JarFileExportOperation_errorSavingDescription, ex);
			}
		}
	}

	private void saveDescription() throws CoreException, IOException {
		// Adjust JAR package attributes
		if (fJarPackage.isManifestReused())
			fJarPackage.setGenerateManifest(false);
		ByteArrayOutputStream objectStreamOutput= new ByteArrayOutputStream();
		IFile descriptionFile= fJarPackage.getDescriptionFile();
		String encoding= "UTF-8"; //$NON-NLS-1$
		try {
			encoding= descriptionFile.getCharset(true);
		} catch (CoreException exception) {
			JavaPlugin.log(exception);
		}
		IJarDescriptionWriter writer= fJarPackage.createJarDescriptionWriter(objectStreamOutput, encoding);
		ByteArrayInputStream fileInput= null;
		try {
			writer.write(fJarPackage);
			fileInput= new ByteArrayInputStream(objectStreamOutput.toByteArray());
			if (descriptionFile.isAccessible()) {
				if (fJarPackage.allowOverwrite() || JarPackagerUtil.askForOverwritePermission(fParentShell, descriptionFile.getFullPath(), false))
					descriptionFile.setContents(fileInput, true, true, null);
			} else
				descriptionFile.create(fileInput, true, null);
		} finally {
			if (fileInput != null)
				fileInput.close();
			if (writer != null)
				writer.close();
		}
	}

	private void saveManifest() throws CoreException, IOException {
		ByteArrayOutputStream manifestOutput= new ByteArrayOutputStream();
		Manifest manifest= fJarPackage.getManifestProvider().create(fJarPackage);
		manifest.write(manifestOutput);
		ByteArrayInputStream fileInput= new ByteArrayInputStream(manifestOutput.toByteArray());
		IFile manifestFile= fJarPackage.getManifestFile();
		if (manifestFile.isAccessible()) {
			if (fJarPackage.allowOverwrite() || JarPackagerUtil.askForOverwritePermission(fParentShell, manifestFile.getFullPath(), false))
				manifestFile.setContents(fileInput, true, true, null);
		} else
			manifestFile.create(fileInput, true, null);
	}
	
	private boolean isAutoBuilding() {
		return ResourcesPlugin.getWorkspace().getDescription().isAutoBuilding();
	}

	private void buildProjects(IProgressMonitor progressMonitor) {
		Set builtProjects= new HashSet(10);
		Object[] elements= fJarPackage.getElements();
		for (int i= 0; i < elements.length; i++) {
			IProject project= null;
			Object element= elements[i];
			if (element instanceof IResource)
				project= ((IResource)element).getProject();
			else if (element instanceof IJavaElement)
				project= ((IJavaElement)element).getJavaProject().getProject();
			if (project != null && !builtProjects.contains(project)) {
				try {
					project.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, progressMonitor);
				} catch (CoreException ex) {
					String message= Messages.format(JarPackagerMessages.JarFileExportOperation_errorDuringProjectBuild, project.getFullPath());
					addError(message, ex);
				} finally {
					// don't try to build same project a second time even if it failed
					builtProjects.add(project);
				}
			}
		}
	}

	/**
	 * Tells whether the given resource (or its children) have compile errors.
	 * The method acts on the current build state and does not recompile.
	 * 
	 * @param resource the resource to check for errors
	 * @return <code>true</code> if the resource (and its children) are error free
	 * @throws CoreException import org.eclipse.core.runtime.CoreException if there's a marker problem
	 */
	private boolean hasCompileErrors(IResource resource) throws CoreException {
		IMarker[] problemMarkers= resource.findMarkers(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, true, IResource.DEPTH_INFINITE);
		for (int i= 0; i < problemMarkers.length; i++) {
			if (problemMarkers[i].getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_ERROR)
				return true;
		}
		return false;
	}

	/**
	 * Tells whether the given resource (or its children) have compile errors.
	 * The method acts on the current build state and does not recompile.
	 * 
	 * @param resource the resource to check for errors
	 * @return <code>true</code> if the resource (and its children) are error free
	 * @throws CoreException import org.eclipse.core.runtime.CoreException if there's a marker problem
	 */
	private boolean hasCompileWarnings(IResource resource) throws CoreException {
		IMarker[] problemMarkers= resource.findMarkers(IJavaModelMarker.JAVA_MODEL_PROBLEM_MARKER, true, IResource.DEPTH_INFINITE);
		for (int i= 0; i < problemMarkers.length; i++) {
			if (problemMarkers[i].getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_WARNING)
				return true;
		}
		return false;
	}

	private boolean mustUseSourceFolderHierarchy() {
		return fJarPackage.useSourceFolderHierarchy() && fJarPackage.areJavaFilesExported() && !fJarPackage.areGeneratedFilesExported();
	}
}
