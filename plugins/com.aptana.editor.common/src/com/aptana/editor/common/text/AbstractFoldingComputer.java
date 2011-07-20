package com.aptana.editor.common.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;

import com.aptana.editor.common.AbstractThemeableEditor;
import com.aptana.editor.common.text.reconciler.IFoldingComputer;
import com.aptana.editor.common.text.reconciler.Messages;
import com.aptana.parsing.ast.IParseNode;
import com.aptana.parsing.ast.ParseRootNode;

/**
 * An abstract base class to use to quickly implement an {@link IFoldingComputer} based on traversing an AST.
 * 
 * @author cwilliams
 */
public abstract class AbstractFoldingComputer implements IFoldingComputer
{

	private IDocument fDocument;
	private AbstractThemeableEditor fEditor;
	private ArrayList<Integer> fLines;
	private boolean initialReconcile;

	public AbstractFoldingComputer(AbstractThemeableEditor editor, IDocument document)
	{
		super();
		this.fEditor = editor;
		this.fDocument = document;
	}

	protected IDocument getDocument()
	{
		return fDocument;
	}

	protected AbstractThemeableEditor getEditor()
	{
		return fEditor;
	}

	protected IParseNode getAST()
	{
		return getEditor().getFileService().getParseResult();
	}

	/*
	 * (non-Javadoc)
	 * @see com.aptana.editor.common.text.reconciler.IFoldingComputer#emitFoldingRegions(boolean,
	 * org.eclipse.core.runtime.IProgressMonitor)
	 */
	public Map<ProjectionAnnotation, Position> emitFoldingRegions(boolean initialReconcile, IProgressMonitor monitor)
			throws BadLocationException
	{
		this.initialReconcile = initialReconcile;
		fLines = new ArrayList<Integer>();
		int lineCount = getDocument().getNumberOfLines();
		if (lineCount <= 1) // Quick hack fix for minified files. We need at least two lines to have folding!
		{
			return Collections.emptyMap();
		}
		SubMonitor sub = null;
		try
		{
			IParseNode parseNode = getAST();
			if (parseNode == null)
			{
				return Collections.emptyMap();
			}
			int length = parseNode.getChildCount();
			if (parseNode instanceof ParseRootNode)
			{
				ParseRootNode prn = (ParseRootNode) parseNode;
				IParseNode[] comments = prn.getCommentNodes();
				if (comments != null && comments.length > 0)
				{
					length += comments.length;
				}
			}
			sub = SubMonitor.convert(monitor, Messages.CommonReconcilingStrategy_FoldingTaskName, length);
			return getPositions(sub.newChild(length), parseNode);
		}
		finally
		{
			fLines = null;
			if (sub != null)
			{
				sub.done();
			}
		}
	}

	protected IParseNode[] getChildren(IParseNode parseNode)
	{
		IParseNode[] children = parseNode.getChildren();
		if (parseNode instanceof ParseRootNode)
		{
			ParseRootNode prn = (ParseRootNode) parseNode;
			IParseNode[] comments = prn.getCommentNodes();
			if (comments != null && comments.length > 0)
			{
				IParseNode[] combined = new IParseNode[children.length + comments.length];
				System.arraycopy(children, 0, combined, 0, children.length);
				System.arraycopy(comments, 0, combined, children.length, comments.length);
				children = combined;
			}
		}
		return children;
	}

	private Map<ProjectionAnnotation, Position> getPositions(IProgressMonitor monitor, IParseNode parseNode)
	{
		Map<ProjectionAnnotation, Position> newPositions = new HashMap<ProjectionAnnotation, Position>();
		IParseNode[] children = getChildren(parseNode);
		SubMonitor sub = SubMonitor.convert(monitor, 2 * children.length);
		for (IParseNode child : children)
		{
			if (sub.isCanceled())
			{
				return newPositions;
			}
			if (isFoldable(child))
			{
				int start = child.getStartingOffset();
				boolean add = true;
				int end = child.getEndingOffset() + 1;
				try
				{
					int line = getDocument().getLineOfOffset(start);
					// Don't bother adding multiple positions for the same starting line
					if (fLines != null && fLines.contains(line))
					{
						add = false;
					}
					else
					{
						// Don't set up folding for stuff starting and ending on same line
						int endLine = getDocument().getLineOfOffset(child.getEndingOffset());
						if (endLine == line)
						{
							add = false;
						}
						else
						{
							// When we can, use the end of the end line as the end offset, so it looks nicer in the
							// editor. Using getLineInformation excludes the line delimiter, so we use the methods that
							// include it!
							end = getDocument().getLineOffset(endLine) + getDocument().getLineLength(endLine);
							if (fLines != null)
							{
								fLines.add(line);
							}
						}
					}
				}
				catch (BadLocationException e)
				{
					// ignore
				}
				if (add)
				{
					end = Math.min(getDocument().getLength(), end);
					newPositions.put(initialReconcile ? new ProjectionAnnotation(isCollapsed(child))
							: new ProjectionAnnotation(), new Position(start, end - start));
				}
			}
			if (traverseInto(child))
			{
				// Recurse into AST!
				newPositions.putAll(getPositions(sub.newChild(1), child));
			}
			sub.worked(1);
		}
		sub.done();
		return newPositions;
	}

	/**
	 * Determine if a certain node type should be collapsed initially.
	 * 
	 * @param child
	 * @return
	 */
	public boolean isCollapsed(IParseNode child)
	{
		return false;
	}

	/**
	 * Is this a node type we want folding for?
	 * 
	 * @param child
	 * @return
	 */
	public abstract boolean isFoldable(IParseNode child);

	/**
	 * Should we try traversing into this node and it's children? Base implementation is yes if it has children.
	 * Subclasses may want to override to avoid traversing into node types we know have children but nothing of interest
	 * for folding.
	 * 
	 * @param child
	 * @return
	 */
	protected boolean traverseInto(IParseNode child)
	{
		if (child == null)
		{
			return false;
		}
		return child.hasChildren();
	}

}