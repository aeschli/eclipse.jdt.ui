/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.jdt.internal.ui.refactoring.nls.search;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;

import org.eclipse.search.ui.IGroupByKeyComputer;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

class GroupByKeyComputer implements IGroupByKeyComputer {

	IJavaElement fLastJavaElement= null;;
	String fLastHandle= null;;

	public Object computeGroupByKey(IMarker marker) {
		// no help from JavaModel to rename yet
		// return getJavaElement(marker);
		return getJavaElementHandleId(marker);
	}

	private String getJavaElementHandleId(IMarker marker) {
		try {
			return (String)marker.getAttribute(INLSSearchUIConstants.ATT_JE_HANDLE_ID);
		} catch (CoreException ex) {
			ExceptionHandler.handle(ex, NLSSearchMessages.getString("Search.Error.markerAttributeAccess.title"), NLSSearchMessages.getString("Search.Error.markerAttributeAccess.message")); //$NON-NLS-2$ //$NON-NLS-1$
			return null;
		}
	}
	
	private IJavaElement getJavaElement(IMarker marker) {
		String handle= getJavaElementHandleId(marker);
		if (handle != null && !handle.equals(fLastHandle)) {
			fLastHandle= handle;
			fLastJavaElement= JavaCore.create(handle);
			IResource handleResource= null;
			try {
				if (fLastJavaElement != null)
					handleResource= fLastJavaElement.getCorrespondingResource();
			} catch (JavaModelException  ex) {
				ExceptionHandler.handle(ex, NLSSearchMessages.getString("Search.Error.javaElementAccess.title"), NLSSearchMessages.getString("Search.Error.javaElementAccess.message")); //$NON-NLS-2$ //$NON-NLS-1$
				// handleResource= null;
			}
			if (fLastJavaElement != null && marker.getResource().equals(handleResource)) {
				// need to get and set new handle here
			}
		}
		return fLastJavaElement;
	}
}