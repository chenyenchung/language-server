/*
 * Copyright 2024-2025, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nextflow.lsp.services.script;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import nextflow.lsp.ast.ASTNodeStringUtils;
import nextflow.lsp.ast.LanguageServerASTUtils;
import nextflow.lsp.services.CompletionProvider;
import nextflow.lsp.util.LanguageServerUtils;
import nextflow.lsp.util.Logger;
import nextflow.script.ast.FunctionNode;
import nextflow.script.ast.InvalidDeclaration;
import nextflow.script.ast.OutputNode;
import nextflow.script.ast.ProcessNode;
import nextflow.script.ast.WorkflowNode;
import nextflow.script.dsl.Constant;
import nextflow.script.dsl.Description;
import nextflow.script.dsl.FeatureFlag;
import nextflow.script.dsl.FeatureFlagDsl;
import nextflow.script.types.TypeChecker;
import nextflow.script.types.Types;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassHelper;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.VariableScope;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.InsertTextMode;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import static nextflow.script.ast.ASTUtils.*;

/**
 * Provide suggestions for an incomplete expression or statement
 * based on available definitions and the surrounding context in
 * the AST.
 *
 * @author Ben Sherman <bentshermann@gmail.com>
 * @author Jordi Deu-Pons <jordi@jordeu.net>
 */
public class ScriptCompletionProvider implements CompletionProvider {

    private static final List<CompletionItem> DECLARATION_SNIPPETS = scriptDeclarationSnippets();

    private static Logger log = Logger.getInstance();

    private ScriptAstCache ast;
    private boolean extended;
    private int maxItems;
    private URI uri;
    private boolean isIncomplete = false;

    public ScriptCompletionProvider(ScriptAstCache ast, int maxItems, boolean extended) {
        this.ast = ast;
        this.extended = extended;
        this.maxItems = maxItems;
    }

    @Override
    public Either<List<CompletionItem>, CompletionList> completion(TextDocumentIdentifier textDocument, Position position) {
        if( ast == null ) {
            log.error("ast cache is empty while providing completions");
            return Either.forLeft(Collections.emptyList());
        }

        this.uri = URI.create(textDocument.getUri());
        if( !ast.hasAST(uri) )
            return Either.forLeft(Collections.emptyList());

        var nodeStack = ast.getNodesAtPosition(uri, position);
        if( nodeStack.isEmpty() )
            return Either.forLeft(DECLARATION_SNIPPETS);

        var offsetNode = nodeStack.get(0);
        var declarationNode = nodeStack.get(nodeStack.size() - 1);

        isIncomplete = false;
        var items = new ArrayList<CompletionItem>();

        if( offsetNode instanceof VariableExpression ve ) {
            // e.g. "foo "
            //          ^
            var namePrefix = ve.getName();
            log.debug("completion variable -- '" + namePrefix + "'");
            populateItemsFromScope(variableScope(nodeStack), namePrefix, declarationNode, items);
        }
        else if( offsetNode instanceof MethodCallExpression mce ) {
            var namePrefix = mce.getMethodAsString();
            log.debug("completion method call -- '" + namePrefix + "'");
            if( mce.isImplicitThis() ) {
                // e.g. "foo ()"
                //          ^
                populateItemsFromScope(variableScope(nodeStack), namePrefix, declarationNode, items);
            }
            else {
                // e.g. "foo.bar ()"
                //              ^
                populateMethodsFromObjectScope(mce.getObjectExpression(), namePrefix, items);
            }
        }
        else if( offsetNode instanceof PropertyExpression pe ) {
            // e.g. "foo.bar "
            //              ^
            var namePrefix = pe.getPropertyAsString();
            log.debug("completion property -- '" + namePrefix + "'");
            populateItemsFromObjectScope(pe.getObjectExpression(), namePrefix, items);
        }
        else if( offsetNode instanceof ConstructorCallExpression cce ) {
            // e.g. "new Foo ()"
            //              ^
            var namePrefix = cce.getType().getNameWithoutPackage();
            log.debug("completion constructor call -- '" + namePrefix + "'");
            populateTypes(namePrefix, items);
        }
        else if( offsetNode instanceof InvalidDeclaration ) {
            return Either.forLeft(Collections.emptyList());
        }
        else {
            log.debug("completion " + offsetNode.getClass().getSimpleName() + " -- '" + offsetNode.getText() + "'");
            populateItemsFromScope(variableScope(nodeStack), "", declarationNode, items);
        }

        return isIncomplete
            ? Either.forRight(new CompletionList(true, items))
            : Either.forLeft(items);
    }

    private void populateItemsFromObjectScope(Expression object, String namePrefix, List<CompletionItem> items) {
        ClassNode cn = TypeChecker.getType(object);
        while( cn != null && !ClassHelper.isObjectType(cn) ) {
            var isStatic = object instanceof ClassExpression;

            for( var fn : cn.getFields() ) {
                if( !fn.isPublic() || isStatic != fn.isStatic() )
                    continue;
                if( !addItemForField(fn, namePrefix, items) )
                    break;
            }

            for( var mn : cn.getMethods() ) {
                if( !mn.isPublic() || isStatic != mn.isStatic() )
                    continue;
                var an = findAnnotation(mn, Constant.class);
                boolean result;
                if( an.isPresent() ) {
                    var name = an.get().getMember("value").getText();
                    result = addItemForConstant(name, mn, namePrefix, items);
                }
                else {
                    result = addItemForMethod(mn, namePrefix, items);
                }
                if( !result )
                    break;
            }

            cn = superClassOrInterface(cn);
        }
    }

    private void populateMethodsFromObjectScope(Expression object, String namePrefix, List<CompletionItem> items) {
        ClassNode cn = TypeChecker.getType(object);
        while( cn != null && !ClassHelper.isObjectType(cn) ) {
            var isStatic = object instanceof ClassExpression;

            for( var mn : cn.getMethods() ) {
                if( !mn.isPublic() || isStatic != mn.isStatic() )
                    continue;
                if( findAnnotation(mn, Constant.class).isPresent() )
                    continue;
                if( !addItemForMethod(mn, namePrefix, items) )
                    break;
            }

            cn = superClassOrInterface(cn);
        }
    }

    private static ClassNode superClassOrInterface(ClassNode cn) {
        if( cn.getSuperClass() != null )
            return cn.getSuperClass();
        if( cn.getInterfaces().length == 1 )
            return cn.getInterfaces()[0];
        return null;
    }

    private boolean addItemForField(FieldNode fn, String namePrefix, List<CompletionItem> items) {
        var name = fn.getName();
        if( !name.startsWith(namePrefix) )
            return true;
        var item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Field);
        item.setLabelDetails(astNodeToItemLabelDetails(fn));
        item.setDetail(astNodeToItemDetail(fn));
        item.setDocumentation(astNodeToItemDocumentation(fn));
        return addItem(item, items);
    }

    private boolean addItemForConstant(String name, MethodNode mn, String namePrefix, List<CompletionItem> items) {
        if( !name.startsWith(namePrefix) )
            return true;
        var fn = new FieldNode(name, 0xF, mn.getReturnType(), mn.getDeclaringClass(), null);
        var item = new CompletionItem(name);
        item.setKind(CompletionItemKind.Constant);
        item.setLabelDetails(astNodeToItemLabelDetails(fn));
        item.setDetail(astNodeToItemDetail(fn));
        item.setDocumentation(astNodeToItemDocumentation(mn));
        return addItem(item, items);
    }

    private boolean addItemForMethod(MethodNode mn, String namePrefix, List<CompletionItem> items) {
        var name = mn.getName();
        if( !name.startsWith(namePrefix) )
            return true;
        var item = new CompletionItem(mn.getName());
        item.setKind(CompletionItemKind.Function);
        item.setLabelDetails(astNodeToItemLabelDetails(mn));
        item.setDetail(astNodeToItemDetail(mn));
        item.setDocumentation(astNodeToItemDocumentation(mn));
        return addItem(item, items);
    }

    private void populateItemsFromScope(VariableScope scope, String namePrefix, ASTNode declarationNode, List<CompletionItem> items) {
        while( scope != null ) {
            populateLocalVariables(scope, namePrefix, items);
            populateItemsFromDslScope(scope.getClassScope(), namePrefix, items);
            scope = scope.getParent();
        }
        populateTypes(namePrefix, items);

        if( !extended ) {
            populateIncludes(namePrefix, items);
            return;
        }
        if( declarationNode instanceof FunctionNode || declarationNode instanceof ProcessNode || declarationNode instanceof OutputNode ) {
            populateExternalFunctions(namePrefix, items);
        }
        if( declarationNode instanceof WorkflowNode ) {
            populateExternalFunctions(namePrefix, items);
            populateExternalProcesses(namePrefix, items);
            populateExternalWorkflows(namePrefix, items);
        }
    }

    private void populateLocalVariables(VariableScope scope, String namePrefix, List<CompletionItem> items) {
        for( var it = scope.getDeclaredVariablesIterator(); it.hasNext(); ) {
            var variable = it.next();
            var name = variable.getName();
            if( !name.startsWith(namePrefix) )
                continue;
            var item = new CompletionItem(name);
            item.setKind(CompletionItemKind.Variable);
            item.setLabelDetails(astNodeToItemLabelDetails(variable));
            if( !addItem(item, items) )
                break;
        }
    }

    private void populateItemsFromDslScope(ClassNode cn, String namePrefix, List<CompletionItem> items) {
        while( cn != null ) {
            for( var mn : cn.getMethods() ) {
                var an = findAnnotation(mn, Constant.class);
                boolean result;
                if( an.isPresent() ) {
                    var name = an.get().getMember("value").getText();
                    result = addItemForConstant(name, mn, namePrefix, items);
                }
                else {
                    result = addItemForMethod(mn, namePrefix, items);
                }
                if( !result )
                    break;
            }
            cn = cn.getInterfaces().length > 0
                ? cn.getInterfaces()[0]
                : null;
        }
    }

    private void populateIncludes(String namePrefix, List<CompletionItem> items) {
        for( var includeNode : ast.getIncludeNodes(uri) ) {
            for( var module : includeNode.modules ) {
                var node = module.getTarget();
                if( node == null )
                    continue;

                var name = module.getNameOrAlias();
                if( !name.startsWith(namePrefix) )
                    continue;

                var labelDetails = new CompletionItemLabelDetails();
                labelDetails.setDescription(getIncludeSource(node));

                var item = new CompletionItem(name);
                item.setKind(CompletionItemKind.Function);
                item.setLabelDetails(labelDetails);
                item.setDetail(astNodeToItemDetail(node));
                item.setDocumentation(astNodeToItemDocumentation(node));

                if( !addItem(item, items) )
                    break;
            }
        }
    }

    private void populateExternalFunctions(String namePrefix, List<CompletionItem> items) {
        var localNodes = ast.getFunctionNodes(uri);
        var allNodes = ast.getFunctionNodes();
        populateExternalMethods(namePrefix, localNodes, allNodes, items);
    }

    private void populateExternalProcesses(String namePrefix, List<CompletionItem> items) {
        var localNodes = ast.getProcessNodes(uri);
        var allNodes = ast.getProcessNodes();
        populateExternalMethods(namePrefix, localNodes, allNodes, items);
    }

    private void populateExternalWorkflows(String namePrefix, List<CompletionItem> items) {
        var localNodes = ast.getWorkflowNodes(uri);
        var allNodes = ast.getWorkflowNodes();
        populateExternalMethods(namePrefix, localNodes, allNodes, items);
    }

    private void populateExternalMethods(String namePrefix, List<? extends MethodNode> localNodes, List<? extends MethodNode> allNodes, List<CompletionItem> items) {
        var addIncludeRange = getAddIncludeRange();

        for( var node : allNodes ) {
            var name = node.getName();
            if( name == null || !name.startsWith(namePrefix) )
                continue;
            if( localNodes.contains(node) )
                continue;

            var labelDetails = new CompletionItemLabelDetails();
            labelDetails.setDescription(getIncludeSource(node));

            var item = new CompletionItem(name);
            item.setKind(CompletionItemKind.Function);
            item.setLabelDetails(labelDetails);
            item.setDetail(astNodeToItemDetail(node));
            item.setDocumentation(astNodeToItemDocumentation(node));

            if( !isIncluded(node) ) {
                var textEdit = createAddIncludeTextEdit(addIncludeRange, name, node);
                item.setAdditionalTextEdits( List.of(textEdit) );
            }

            if( !addItem(item, items) )
                break;
        }
    }

    private boolean isIncluded(MethodNode node) {
        for( var includeNode : ast.getIncludeNodes(uri) ) {
            for( var module : includeNode.modules ) {
                if( module.getTarget() == node )
                    return true;
            }
        }
        return false;
    }

    private Range getAddIncludeRange() {
        var includeNodes = ast.getIncludeNodes(uri);
        if( includeNodes.isEmpty() ) {
            var line = ast.getScriptNode(uri).getShebang() != null ? 1 : 0;
            return new Range(new Position(line, 0), new Position(line, 0));
        }
        var lastInclude = includeNodes.get(includeNodes.size() - 1);
        var lastIncludeRange = LanguageServerUtils.astNodeToRange(lastInclude);
        var includeLine = lastIncludeRange != null ? lastIncludeRange.getEnd().getLine() + 1 : 0;
        return new Range(new Position(includeLine, 0), new Position(includeLine, 0));
    }

    private TextEdit createAddIncludeTextEdit(Range range, String name, ASTNode node) {
        var newText = new StringBuilder()
            .append("include { ")
            .append(name)
            .append(" } from '")
            .append(getIncludeSource(node))
            .append("'\n")
            .toString();
        return new TextEdit(range, newText);
    }

    private String getIncludeSource(ASTNode node) {
        var source = Path.of(uri).getParent().relativize(Path.of(ast.getURI(node))).toString();
        return source.startsWith(".")
            ? source
            : "./" + source;
    }

    private void populateTypes(String namePrefix, List<CompletionItem> items) {
        // add user-defined types
        populateTypes0(ast.getEnumNodes(uri), namePrefix, items);

        // add built-in types
        populateTypes0(Types.DEFAULT_SCRIPT_IMPORTS, namePrefix, items);
    }

    private void populateTypes0(Collection<ClassNode> classNodes, String namePrefix, List<CompletionItem> items) {
        for( var cn : classNodes ) {
            var item = new CompletionItem(cn.getNameWithoutPackage());
            item.setKind(astNodeToItemKind(cn));
            item.setDocumentation(astNodeToItemDocumentation(cn));

            if( !addItem(item, items) )
                break;
        }
    }

    private static VariableScope variableScope(List<ASTNode> nodeStack) {
        for( var node : nodeStack ) {
            if( node instanceof BlockStatement block )
                return block.getVariableScope();
        }
        return null;
    }

    private static String astNodeToItemDetail(ASTNode node) {
        return ASTNodeStringUtils.getLabel(node);
    }

    private static MarkupContent astNodeToItemDocumentation(ASTNode node) {
        var documentation = ASTNodeStringUtils.getDocumentation(node);
        return documentation != null
            ? new MarkupContent(MarkupKind.MARKDOWN, documentation)
            : null;
    }

    private static CompletionItemKind astNodeToItemKind(ASTNode node) {
        if( node instanceof ClassNode cn ) {
            return cn.isEnum()
                ? CompletionItemKind.Enum
                : CompletionItemKind.Class;
        }
        if( node instanceof MethodNode ) {
            return CompletionItemKind.Method;
        }
        if( node instanceof Variable ) {
            return node instanceof FieldNode
                ? CompletionItemKind.Field
                : CompletionItemKind.Variable;
        }
        return CompletionItemKind.Property;
    }

    private static CompletionItemLabelDetails astNodeToItemLabelDetails(Object node) {
        var result = new CompletionItemLabelDetails();
        if( node instanceof ProcessNode pn ) {
            result.setDescription("process");
        }
        else if( node instanceof WorkflowNode pn ) {
            result.setDescription("workflow");
        }
        else if( node instanceof MethodNode mn ) {
            result.setDetail("(" + ASTNodeStringUtils.parametersToLabel(mn.getParameters()) + ")");
            if( Types.hasReturnType(mn) )
                result.setDescription(Types.getName(mn.getReturnType()));
        }
        else if( node instanceof Variable variable ) {
            result.setDescription(Types.getName(variable.getType()));
        }
        return result;
    }

    private boolean addItem(CompletionItem item, List<CompletionItem> items) {
        if( items.size() >= maxItems ) {
            isIncomplete = true;
            return false;
        }
        items.add(item);
        return true;
    }

    private static List<CompletionItem> scriptDeclarationSnippets() {
        var snippets = new ArrayList<Snippet>();

        snippets.add(new Snippet(
            "shebang",
            """
            Shebang declaration:

            ```sh
            #!/usr/bin/env nextflow
            ```
            """,
            "#!/usr/bin/env nextflow\n\n"
        ));
        snippets.add(new Snippet(
            "include",
            """
            Include declaration:

            ```nextflow
            include { HELLO } from './hello.nf'
            ```

            [Read more](https://nextflow.io/docs/latest/module.html#module-inclusion)
            """,
            "include { $1 } from './$2'"
        ));
        snippets.add(new Snippet(
            "def",
            """
            Function definition:

            ```nextflow
            def greet(greeting, name) {
                println "${greeting}, ${name}!"
            }
            ```

            [Read more](https://nextflow.io/docs/latest/script.html#functions)
            """,
            """
            def $1($2) {
                $3
            }
            """
        ));
        snippets.add(new Snippet(
            "process",
            """
            Process definition:

            ```nextflow
            process HELLO {
                input:
                val message

                output:
                stdout

                script:
                \"\"\"
                echo '$message world!'
                \"\"\"
            }
            ```

            [Read more](https://nextflow.io/docs/latest/process.html)
            """,
            """
            process $1 {
                input:
                ${2|val,path,env,stdin,tuple|} ${3:identifier}

                output:
                ${4|val,path,env,stdout,tuple|} ${5:identifier}

                script:
                \"\"\"
                $6
                \"\"\"

                stub:
                \"\"\"
                $7
                \"\"\"
            }
            """
        ));
        snippets.add(new Snippet(
            "workflow",
            """
            Workflow definition:

            ```nextflow
            workflow MY_FLOW {
                take:
                input

                main:
                input | view | set { output }

                emit:
                output
            }
            ```

            [Read more](https://nextflow.io/docs/latest/workflow.html)
            """,
            """
            workflow $1 {
                take:
                $2

                main:
                $3

                emit:
                $4
            }
            """
        ));
        snippets.add(new Snippet(
            "workflow <entry>",
            """
            Entry workflow definition:

            ```nextflow
            workflow {
                Channel.of('Bonjour', 'Ciao', 'Hello', 'Hola') | view { v -> "$v world!" }
            }
            ```

            [Read more](https://nextflow.io/docs/latest/workflow.html)
            """,
            """
            workflow {
                $1
            }
            """
        ));
        snippets.add(new Snippet(
            "output",
            """
            Output block for configuring publish targets:

            ```nextflow
            output {
                'fastq' {
                    path 'samples'
                    index {
                        path 'index.csv'
                    }
                }
            }
            ```

            [Read more](https://nextflow.io/docs/latest/workflow.html#workflow-output-def)
            """,
            """
            output {
                $1
            }
            """
        ));

        for( var field : FeatureFlagDsl.class.getDeclaredFields() ) {
            var name = field.getAnnotation(FeatureFlag.class);
            var description = field.getAnnotation(Description.class);
            if( name == null || description == null )
                continue;
            snippets.add(new Snippet(
                name.value(),
                description.value(),
                name.value() + " = "
            ));
        }

        var result = new ArrayList<CompletionItem>();
        for( var snippet : snippets ) {
            var item = new CompletionItem(snippet.name());
            item.setKind(CompletionItemKind.Snippet);
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, StringGroovyMethods.stripIndent(snippet.documentation(), true).trim()));
            item.setInsertText(StringGroovyMethods.stripIndent(snippet.insertText(), true).trim());
            item.setInsertTextFormat(InsertTextFormat.Snippet);
            item.setInsertTextMode(InsertTextMode.AdjustIndentation);
            result.add(item);
        }
        return result;
    }

    private static record Snippet(
        String name,
        String documentation,
        String insertText
    ) {}

}
