/* 
 * eXist Open Source Native XML Database
 * 
 * Copyright (C) 2000-04,  Wolfgang Meier (wolfgang@exist-db.org)
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 * $Id$
 */

package org.exist.dom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.TreeSet;

import org.exist.collections.Collection;
import org.exist.security.Permission;
import org.exist.storage.DBBroker;
import org.exist.storage.lock.Lock;
import org.exist.util.LockException;
import org.exist.util.hashtable.Int2ObjectHashMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Manages a set of documents.
 * 
 * This class implements the NodeList interface for a collection of documents.
 * It also contains methods to retrieve the collections these documents
 * belong to.
 * 
 * @author wolf
 */
public class DocumentSet extends Int2ObjectHashMap implements NodeList {

    public final static DocumentSet EMPTY_DOCUMENT_SET = new DocumentSet(9);
	
	private ArrayList list = null;
	private TreeSet collections = new TreeSet();
	
	public DocumentSet() {
		super(29, 1.75);
	}

	public DocumentSet(int initialSize) {
	    super(initialSize, 1.75);
	}
	
	public void clear() {
		super.clear();
		collections = new TreeSet();
		list = null;
	}

	public void add(DocumentImpl doc) {
	    add(doc, true);
	}
	
	public void add(DocumentImpl doc, boolean checkDuplicates) {
		final int docId = doc.getDocId();
		if (checkDuplicates && containsKey(docId))
			return;
		put(docId, doc);
		if (list != null)
			list.add(doc);
		if (doc.getCollection() != null
			&& (!collections.contains(doc.getCollection())))
			collections.add(doc.getCollection());
	}

	public void add(Node node) {
		if (!(node instanceof DocumentImpl))
			throw new RuntimeException("wrong implementation");
		add((DocumentImpl) node);
	}

	public void addAll(NodeList other) {
		for (int i = 0; i < other.getLength(); i++)
			add(other.item(i));
	}
	
	/**
	 * Fast method to add a bunch of documents from a
	 * Java collection.
	 * 
	 * The method assumes that no duplicate entries are
	 * in the input collection.
	 * 
	 * @param docs
	 */
	public void addAll(DBBroker broker, java.util.Collection docs, boolean checkPermissions) {
		DocumentImpl doc;
		for(Iterator i = docs.iterator(); i.hasNext(); ) {
			doc = (DocumentImpl)i.next();
//			    if(doc.isLockedForWrite())
//			        continue;
		    if(broker == null || !checkPermissions || 
		        doc.getPermissions().validate(broker.getUser(), Permission.READ)) {
		        put(doc.getDocId(), doc);
		    }
		}
	}

	public void addCollection(Collection collection) {
		collections.add(collection);
	}

	public Iterator iterator() {
		return valueIterator();
	}

	public Iterator getCollectionIterator() {
		return collections.iterator();
	}

	public int getLength() {
		return size();
	}

	public int getCollectionCount() {
		return collections.size();
	}
	
	public Node item(int pos) {
		if (list == null) {
			list = new ArrayList();
			for(Iterator i = valueIterator(); i.hasNext(); )
				list.add(i.next());
		}
		return (Node) list.get(pos);
	}

	public DocumentImpl getDoc(int docId) {
		return (DocumentImpl) get(docId);
	}

	public String[] getNames() {
		String result[] = new String[size()];
		DocumentImpl d;
		int j = 0;
		for (Iterator i = iterator(); i.hasNext(); j++) {
			d = (DocumentImpl) i.next();
			result[j] = d.getFileName();
		}
		Arrays.sort(result);
		return result;
	}

	public DocumentSet intersection(DocumentSet other) {
		DocumentSet r = new DocumentSet();
		DocumentImpl d;
		for (Iterator i = iterator(); i.hasNext();) {
			d = (DocumentImpl) i.next();
			if (other.containsKey(d.getDocId()))
				r.add(d);
		}
		for (Iterator i = other.iterator(); i.hasNext();) {
			d = (DocumentImpl) i.next();
			if (containsKey(d.getDocId()) && (!r.containsKey(d.getDocId())))
				r.add(d);
		}
		return r;
	}

	public DocumentSet union(DocumentSet other) {
		DocumentSet result = new DocumentSet();
		result.addAll(other);
		DocumentImpl d;
		for (Iterator i = iterator(); i.hasNext();) {
			d = (DocumentImpl) i.next();
			if (!result.containsKey(d.getDocId()))
				result.add(d);
		}
		return result;
	}

	public boolean contains(DocumentSet other) {
		if (other.size() > size())
			return false;
		DocumentImpl d;		
		for (Iterator i = other.iterator(); i.hasNext();) {
			d = (DocumentImpl) i.next();
			if (!containsKey(d.getDocId()))
				return false;
		}
		return true;
	}

	public boolean contains(int id) {
		return containsKey(id);
	}

	public NodeSet toNodeSet() {
		NodeSet result = new ExtArrayNodeSet(getLength());
		DocumentImpl doc;
        for (Iterator i = iterator(); i.hasNext();) {
            doc = (DocumentImpl) i.next();
            if(doc.getResourceType() == DocumentImpl.XML_FILE) {  // skip binary resources
            	result.add(new NodeProxy(doc, NodeProxy.DOCUMENT_NODE_GID));
            }
        }
        return result;
	}
	
	public int getMinDocId() {
		int min = DocumentImpl.UNKNOWN_DOCUMENT_ID; 
        DocumentImpl d;
		for (Iterator i = iterator(); i.hasNext();) {
			d = (DocumentImpl) i.next();
			if (min == DocumentImpl.UNKNOWN_DOCUMENT_ID)
				min = d.getDocId();
			else if (d.getDocId() < min)
				min = d.getDocId();
		}
		return min;
	}

	public int getMaxDocId() {
		int max = DocumentImpl.UNKNOWN_DOCUMENT_ID;
		DocumentImpl d;
		for (Iterator i = iterator(); i.hasNext();) {
			d = (DocumentImpl) i.next();
			if (d.getDocId() > max)
				max = d.getDocId();
		}
		return max;
	}

	public boolean equals(Object other) {
		if (this == other)
			// we are comparing the same objects
			return true;
		final DocumentSet o = (DocumentSet) other;
		if (size() != o.size())
			return false;
		return hasEqualKeys(o);
	}
	
	public void lock(boolean exclusive) throws LockException {
	    DocumentImpl d;
	    Lock dlock;
	    for(int idx = 0; idx < tabSize; idx++) {
	        if(values[idx] == null || values[idx] == REMOVED)
	            continue;
	        d = (DocumentImpl)values[idx];
	        dlock = d.getUpdateLock();
	        if(exclusive)
	            dlock.acquire(Lock.WRITE_LOCK);
	        else
	            dlock.acquire(Lock.READ_LOCK);
	    }
	}
	
	public void unlock(boolean exclusive) {
	    DocumentImpl d;
	    Lock dlock;
	    for(int idx = 0; idx < tabSize; idx++) {
	        if(values[idx] == null || values[idx] == REMOVED)
	            continue;
	        d = (DocumentImpl)values[idx];
	        dlock = d.getUpdateLock();
	        if(exclusive)
	            dlock.release(Lock.WRITE_LOCK);
	        else
	            dlock.release(Lock.READ_LOCK);
	    }
	}
}
