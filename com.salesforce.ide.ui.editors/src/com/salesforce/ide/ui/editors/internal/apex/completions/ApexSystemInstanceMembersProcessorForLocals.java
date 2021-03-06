/*******************************************************************************
 * Copyright (c) 2015 Salesforce.com, inc..
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Salesforce.com, inc. - initial API and implementation
 ******************************************************************************/
package com.salesforce.ide.ui.editors.internal.apex.completions;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.internal.ui.viewsupport.JavaElementImageProvider;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContentAssistProcessor;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.texteditor.ITextEditor;

import apex.jorje.data.Loc;
import apex.jorje.data.Loc.RealLoc;
import apex.jorje.semantic.ast.compilation.Compilation;
import apex.jorje.semantic.ast.compilation.UserClass;
import apex.jorje.semantic.ast.compilation.UserTrigger;
import apex.jorje.semantic.ast.member.FormalParameterNode;
import apex.jorje.semantic.ast.member.Method;
import apex.jorje.semantic.ast.statement.BlockStatement;
import apex.jorje.semantic.ast.statement.ForEachStatement;
import apex.jorje.semantic.ast.statement.ForLoopStatement;
import apex.jorje.semantic.ast.statement.IfBlockStatement;
import apex.jorje.semantic.ast.statement.IfElseBlockStatement;
import apex.jorje.semantic.ast.statement.RunAsBlockStatement;
import apex.jorje.semantic.ast.statement.TryCatchFinallyBlockStatement;
import apex.jorje.semantic.ast.statement.VariableDeclaration;
import apex.jorje.semantic.ast.statement.VariableDeclarationStatements;
import apex.jorje.semantic.ast.statement.WhileLoopStatement;
import apex.jorje.semantic.ast.visitor.AstVisitor;
import apex.jorje.semantic.ast.visitor.SymbolScope;
import apex.jorje.semantic.compiler.ApexCompiler;
import apex.jorje.semantic.compiler.CompilationInput;
import apex.jorje.semantic.compiler.SourceFile;
import apex.jorje.semantic.symbol.member.variable.LocalInfo;
import apex.jorje.semantic.symbol.resolver.SymbolResolverImpl;
import apex.jorje.semantic.symbol.type.TypeInfo;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.salesforce.ide.apex.internal.core.ApexModelManager;
import com.salesforce.ide.apex.internal.core.tooling.systemcompletions.model.AbstractCompletionProposalDisplayable;
import com.salesforce.ide.ui.editors.internal.apex.completions.ApexSystemInstanceMembersProcessorForLocals.LocalVariablesVisitor.LocalInfoWrapper;
import com.salesforce.ide.ui.internal.ForceImages;
import com.salesforce.ide.ui.internal.editor.imagesupport.ApexElementImageDescriptor;

/**
 * <p>
 * Provides system completions for instance members (properties and non-static methods) of variables. This is an initial
 * implementation while we aim for tighter integration with the compiler. Thus, it has the following limitations:
 * <ol>
 * <li>Only works with system types</li>
 * <li>Does not understand chaining, e.g. myVar.anInstanceField.anotherInstanceField</li>
 * <li>Finest level of granularity is method-level, so you might see all variables that are declared inside a method
 * even if they technically have different scopes.</li>
 * </ol>
 * </p>
 * 
 * <p>
 * This works for locals inside methods. It tries to keep track of where the locals are.
 * </p>
 * 
 * @author nchen
 * 
 */
public class ApexSystemInstanceMembersProcessorForLocals extends ApexCompletionProcessor implements
        IContentAssistProcessor {
    private static final class VariableNamePredicate implements Predicate<AbstractCompletionProposalDisplayable> {
        private final String variablePrefix;

        private VariableNamePredicate(String variablePrefix) {
            this.variablePrefix = variablePrefix;
        }

        @Override
        public boolean apply(AbstractCompletionProposalDisplayable proposal) {
            return proposal.getReplacementString().toLowerCase().startsWith(variablePrefix);
        }
    }

    private final ITextEditor editor;
    private LocalVariablesVisitor visitor;

    public ApexSystemInstanceMembersProcessorForLocals(ITextEditor editor) {
        this.editor = editor;
    }

    public Compilation getCompilationUnit(IResource resource) {
        return ApexModelManager.INSTANCE.getCompilation((IFile) resource);
    }

    @Override
    public ICompletionProposal[] computeCompletionProposals(ITextViewer viewer, int offset) {
        Collection<AbstractCompletionProposalDisplayable> suggestions = Lists.newArrayList();
        ApexCompletionUtils.CompletionPrefix completionPrefix = null;

        visitVariables(viewer);

        try {
            if (!getUtil().hasInvokedNewOnSameLine(viewer, offset)) {
                final String prefix = getUtil().getPrefix(viewer, offset);
                completionPrefix = getUtil().determineFullyQualifiedNameFromPrefix(prefix);
                Collection<AbstractCompletionProposalDisplayable> locals = determineWhichLocalsApply(viewer, offset);

                if (completionPrefix.shouldSuggestVariableName()) {
                    final String variablePrefix = completionPrefix.segments.get(0);
                    suggestions = Collections2.filter(locals, new VariableNamePredicate(variablePrefix));
                    return getUtil().createProposal(suggestions, prefix, offset, getLocalsImage());
                } else if (completionPrefix.shouldSuggestVariableMember()) {
                    final String variableName = completionPrefix.segments.get(0);
                    String memberPrefix = completionPrefix.segments.get(1);

                    AbstractCompletionProposalDisplayable found =
                            Iterables.find(locals, new VariableNamePredicate(variableName));

                    if (found != null) {
                        LocalInfoWrapper wrapper = (LocalInfoWrapper) found;
                        TypeInfo typeInfo = wrapper.localInfo.getType();

                        Collection<AbstractCompletionProposalDisplayable> accept =
                                typeInfo.accept(new TypeInfoUtil.SystemsInstanceMembersCompletionSuggestor(memberPrefix));
                        return getUtil().createProposal(accept, memberPrefix, offset, getMembersImage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error trying to generate auto-completion for local variables", e);
        }

        return new ICompletionProposal[0];
    }

    private Collection<AbstractCompletionProposalDisplayable> determineWhichLocalsApply(ITextViewer viewer, int offset) {
        try {
            int methodScope = -1;
            int line = viewer.getDocument().getLineOfOffset(offset);
            for (Integer methodOffset : visitor.locals.keySet()) {
                if (line >= methodOffset) {
                    methodScope = methodOffset;
                }
            }
            if (methodScope != -1) // possible when we don't have any methods declared yet
                return visitor.locals.get(methodScope);
        } catch (BadLocationException e) {
            logger.warn("Error trying to determine the applicable locals for this offset", e);
        }

        return Collections.emptyList();
    }

    public Image getLocalsImage() {
        int accessorFlags_JVM = Flags.AccDefault;
        int accessorFlags_JDT = 0;

        ImageDescriptor desc = JavaElementImageProvider.getFieldImageDescriptor(false, accessorFlags_JVM);
        ApexElementImageDescriptor decoratedDesc =
                new ApexElementImageDescriptor(desc, accessorFlags_JDT, APEX_ICON_SIZE);
        return ForceImages.get(ForceImages.JDT_LOCAL_VAR, accessorFlags_JVM, decoratedDesc);
    }

    public Image getMembersImage() {
        int accessorFlags_JVM = Flags.AccPublic;
        int accessorFlags_JDT = 0;

        ImageDescriptor desc = ForceImages.getDesc(ForceImages.APEX_GLOBAL_METHOD);
        ApexElementImageDescriptor decoratedDesc =
                new ApexElementImageDescriptor(desc, accessorFlags_JDT, APEX_ICON_SIZE);
        return ForceImages.get(ForceImages.APEX_GLOBAL_METHOD, accessorFlags_JVM, decoratedDesc);
    }

    protected void visitVariables(ITextViewer viewer) {
        IResource resource = (IResource) editor.getEditorInput().getAdapter(IResource.class);
        SourceFile virtualSourceFile =
                SourceFile.builder().setSource(viewer.getDocument().get())
                        .setNamespace(apex.jorje.semantic.compiler.Namespace.EMPTY).build();
        Compilation compilation = getCompilationUnit(resource);
        ApexCompiler compiler =
                ApexCompiler
                        .builder()
                        .setInput(
                            new CompilationInput(Collections.singleton(virtualSourceFile), EmptySymbolProvider.get(),
                                    null, null)).build();
        SymbolScope scope = new SymbolScope(new SymbolResolverImpl(compiler));
        visitor = new LocalVariablesVisitor();
        compilation.traverse(visitor, scope);
    }

    /**
     * Determines the "type" of the declared variable. This currently operates only on the current file and doesn't
     * traverse to the parents or references.
     * 
     * @author nchen
     * 
     */
    static class LocalVariablesVisitor extends AstVisitor<SymbolScope> {
        static class LocalInfoWrapper extends AbstractCompletionProposalDisplayable {
            LocalInfo localInfo;

            public LocalInfoWrapper(LocalInfo localInfo) {
                this.localInfo = localInfo;
            }

            @Override
            public String getReplacementString() {
                return localInfo.getName();
            }

            @Override
            public String getDisplayString() {
                return null; // Maybe display type information
            }

            public TypeInfo getType() {
                return localInfo.getType();
            }
        }

        Map<Integer, Collection<AbstractCompletionProposalDisplayable>> locals = Maps.newTreeMap();
        Collection<AbstractCompletionProposalDisplayable> currentScopeLocalsCollector;
        Integer currentMethodPosition;

        // To enable completions with details from classes and triggers, we tell the visitor that we want to "visit" the innards of those elements
        @Override
        public boolean visit(UserClass node, SymbolScope scope) {
            return true;
        }

        @Override
        public boolean visit(UserTrigger node, SymbolScope scope) {
            return true;
        }

        /*
         * There are two special methods that are "visited" even though we didn't necessarily declare them so we have to be mindful: clinit and clone.
         */
        @Override
        public boolean visit(Method node, SymbolScope scope) {
            node.resolve(scope.getSymbols());
            node.getLoc()._switch(new Loc.SwitchBlockWithDefault() {

                @Override
                public void _case(RealLoc x) {
                    currentScopeLocalsCollector = Lists.newArrayList();
                    currentMethodPosition = x.line;
                }

                @Override
                protected void _default(Loc x) {}
            });
            return true;
        }

        @Override
        public void visitEnd(Method node, SymbolScope scope) {
            super.visitEnd(node, scope);
            if (currentScopeLocalsCollector != null) {
                locals.put(currentMethodPosition, currentScopeLocalsCollector);
            }
        }

        @Override
        public boolean visit(BlockStatement node, SymbolScope scope) {
            return true;
        }

        @Override
        public boolean visit(ForEachStatement node, SymbolScope scope) {
            return true;
        }

        @Override
        public boolean visit(ForLoopStatement node, SymbolScope scope) {
            return true;
        }

        @Override
        public boolean visit(IfBlockStatement node, SymbolScope scope) {
            return true;
        }

        @Override
        public boolean visit(IfElseBlockStatement node, SymbolScope scope) {
            return true;
        }

        @Override
        public boolean visit(RunAsBlockStatement node, SymbolScope scope) {
            return true;
        }

        @Override
        public boolean visit(WhileLoopStatement node, SymbolScope scope) {
            return true;
        }

        @Override
        public boolean visit(TryCatchFinallyBlockStatement node, SymbolScope scope) {
            return true;
        }

        /*
         * This doesn't work for the parameters since FormalParameterNode doesn't produce a LocalInfo.
         */
        @Override
        public boolean visit(FormalParameterNode node, SymbolScope scope) {
            return true;
        }

        @Override
        public boolean visit(VariableDeclaration node, SymbolScope scope) {
            node.createLocal(scope.getSymbols());
            LocalInfo localInfo = node.getLocalInfo();
            if (localInfo != null) {
                currentScopeLocalsCollector.add(new LocalInfoWrapper(localInfo));
            }
            return true;
        }

        @Override
        public boolean visit(VariableDeclarationStatements node, SymbolScope scope) {
            node.resolveType(scope.getSymbols());
            return true;
        }
    }
}
