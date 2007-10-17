/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ltk.internal.core.refactoring.resource;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.IPath;

import org.eclipse.ltk.core.refactoring.RefactoringContribution;
import org.eclipse.ltk.core.refactoring.RefactoringDescriptor;
import org.eclipse.ltk.core.refactoring.resource.DeleteResourcesDescriptor;

public class DeleteResourcesRefactoringContribution extends RefactoringContribution {
	private static final String TRUE= "true"; //$NON-NLS-1$
	private static final String FALSE= "false"; //$NON-NLS-1$
	private static final String ATTRIBUTE_DELETE_CONTENTS= "deleteContents"; //$NON-NLS-1$
	private static final String ATTRIBUTE_FORCE_OUT_OF_SYNC= "force"; //$NON-NLS-1$
	/**
	 * Key used for the number of resource to be deleted
	 */
	private static final String ATTRIBUTE_NUMBER_OF_RESOURCES= "resources"; //$NON-NLS-1$

	/**
	 * Key used for the path of the resource to be deleted
	 */
	private static final String ATTRIBUTE_ELEMENT= "element"; //$NON-NLS-1$

	/* (non-Javadoc)
	 * @see org.eclipse.ltk.core.refactoring.RefactoringContribution#retrieveArgumentMap(org.eclipse.ltk.core.refactoring.RefactoringDescriptor)
	 */
	public Map retrieveArgumentMap(RefactoringDescriptor descriptor) {
		if (descriptor instanceof DeleteResourcesDescriptor) {
			DeleteResourcesDescriptor deleteDesc= (DeleteResourcesDescriptor) descriptor;
			HashMap map= new HashMap();
			IPath[] resources= deleteDesc.getResourcePaths();
			String project= deleteDesc.getProject();
			map.put(ATTRIBUTE_NUMBER_OF_RESOURCES, String.valueOf(resources.length));
			for (int i= 0; i < resources.length; i++) {
				map.put(ATTRIBUTE_ELEMENT + (i + 1), ResourceProcessors.resourcePathToHandle(project, resources[i]));
			}

			map.put(ATTRIBUTE_FORCE_OUT_OF_SYNC, deleteDesc.isForceOutOfSync() ? TRUE : FALSE);
			map.put(ATTRIBUTE_DELETE_CONTENTS, deleteDesc.isDeleteContents() ? TRUE : FALSE);
			return map;
		}
		return Collections.EMPTY_MAP;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ltk.core.refactoring.RefactoringContribution#createDescriptor()
	 */
	public RefactoringDescriptor createDescriptor() {
		return new DeleteResourcesDescriptor();
	}

	/* (non-Javadoc)
	 * @see org.eclipse.ltk.core.refactoring.RefactoringContribution#createDescriptor(java.lang.String, java.lang.String, java.lang.String, java.lang.String, java.util.Map, int)
	 */
	public RefactoringDescriptor createDescriptor(String id, String project, String description, String comment, Map arguments, int flags) throws IllegalArgumentException {
		String force= (String) arguments.get(ATTRIBUTE_FORCE_OUT_OF_SYNC);
		String del= (String) arguments.get(ATTRIBUTE_DELETE_CONTENTS);

		try {
			int numResources= Integer.parseInt((String) arguments.get(ATTRIBUTE_NUMBER_OF_RESOURCES));
			if (numResources < 0 || numResources > 100000) {
				throw new IllegalArgumentException("Can not restore DeleteResourcesDescriptor from map, number of moved elements invalid"); //$NON-NLS-1$
			}

			IPath[] resourcePaths= new IPath[numResources];
			for (int i= 0; i < numResources; i++) {
				String resource= (String) arguments.get(ATTRIBUTE_ELEMENT + String.valueOf(i));
				if (resource == null) {
					throw new IllegalArgumentException("Can not restore DeleteResourcesDescriptor from map, resource missing"); //$NON-NLS-1$
				}
				resourcePaths[i]= ResourceProcessors.handleToResourcePath(project, resource);
			}

			if (resourcePaths.length > 0) {
				DeleteResourcesDescriptor descriptor= new DeleteResourcesDescriptor();
				descriptor.setProject(project);
				descriptor.setDescription(description);
				descriptor.setComment(comment);
				descriptor.setFlags(flags);
				descriptor.setResourcePaths(resourcePaths);
				descriptor.setForceOutOfSync(TRUE.equals(force));
				descriptor.setDeleteContents(TRUE.equals(del));
				return descriptor;
			}
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Can not restore DeleteResourcesDescriptor from map"); //$NON-NLS-1$
		}
		throw new IllegalArgumentException("Can not restore DeleteResourceDescriptor from map"); //$NON-NLS-1$
	}
}