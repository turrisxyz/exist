/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2001-06 The eXist Project
 *  http://exist-db.org
 *  http://exist.sourceforge.net
 *  
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 *  
 *  $Id$
 */
package org.exist.xquery;

/**
 * Holds context information and execution hints for XQuery expressions.
 * Instances of this class are passed to {@link Expression#analyze(AnalyzeContextInfo)}
 * during the analysis phase of the query.
 * 
 * @author wolf
 *
 */
public class AnalyzeContextInfo {
	
	private Expression parent = null;
	private int flags = 0;
	private int contextId = Expression.NO_CONTEXT_ID;
	
	public AnalyzeContextInfo() {
	}
	
	/**
	 * Create a new AnalyzeContextInfo using the given parent and flags.
	 * 
	 * @param parent the parent expression which calls this method
     * @param flags int value containing a set of flags. See the constants defined
     * in this class.
	 */
	public AnalyzeContextInfo(Expression parent, int flags) {
		this.parent = parent;
		this.flags = flags;
	}

	/**
	 * Create a new object as a clone of other.
	 * 
	 * @param other
	 */
	public AnalyzeContextInfo(AnalyzeContextInfo other) {
		this.parent = other.parent;
		this.flags = other.flags;
		this.contextId = other.contextId;
	}
	
	/**
	 * Returns the current context id. The context id is used
	 * to keep track of the context node set within a predicate
	 * expression or where-clause. The id identifies the ancestor 
	 * expression to which the context applies.
	 * 
	 * @return
	 */
	public int getContextId() {
		return contextId;
	}

	public void setContextId(int contextId) {
		this.contextId = contextId;
	}

	/**
	 * Returns the processing flags. Every expression may pass
	 * execution hints to its child expressions, encoded as bit flags. 
	 * 
	 * @return
	 */
	public int getFlags() {
		return flags;
	}

	/**
	 * Sets the processing flags to be passed to a child expression.
	 * 
	 * @param flags
	 */
	public void setFlags(int flags) {
		this.flags = flags;
	}

	public void addFlag(int flag) {
		flags |= flag;
	}
	
	public void removeFlag(int flag) {
		flags &= ~flag;
	}
	
	/**
	 * Returns the parent of the current expression.
	 * @return
	 */
	public Expression getParent() {
		return parent;
	}

	public void setParent(Expression parent) {
		this.parent = parent;
	}

}
