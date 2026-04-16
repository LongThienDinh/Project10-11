import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class JackCompiler {
    private static final Set<String> KEYWORDS = Set.of(
            "class", "constructor", "function", "method", "field", "static", "var",
            "int", "char", "boolean", "void", "true", "false", "null", "this",
            "let", "do", "if", "else", "while", "return"
    );

    private static final Set<Character> SYMBOLS = new HashSet<>(Arrays.asList(
            '{', '}', '(', ')', '[', ']', '.', ',', ';', '+', '-', '*', '/', '&',
            '|', '<', '>', '=', '~'
    ));

    private static final Set<String> OP_SET = Set.of("+", "-", "*", "/", "&", "|", "<", ">", "=");
    private static final Set<String> UNARY_OP_SET = Set.of("-", "~");
    private static final Set<String> KEYWORD_CONSTANTS = Set.of("true", "false", "null", "this");

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        Mode mode = Mode.COMPILE_VM;
        Path input = null;

        for (String arg : args) {
            switch (arg) {
                case "--xml":
                    mode = Mode.PARSE_XML;
                    break;
                case "--tokens":
                    mode = Mode.TOKENS_XML;
                    break;
                case "--help":
                case "-h":
                    printUsage();
                    return;
                default:
                    if (input != null) {
                        throw new IllegalArgumentException("Only one input path is supported.");
                    }
                    input = Paths.get(arg);
                    break;
            }
        }

        if (input == null) {
            throw new IllegalArgumentException("Missing input path.");
        }

        List<Path> jackFiles = collectJackFiles(input);
        if (jackFiles.isEmpty()) {
            throw new IllegalArgumentException("No .jack files found at: " + input);
        }

        for (Path jackFile : jackFiles) {
            compileOne(jackFile, mode);
        }
    }

    private static void printUsage() {
        System.out.println("JackCompiler (single-file variant)");
        System.out.println("Usage:");
        System.out.println("  java JackCompiler <file.jack | directory>");
        System.out.println("  java JackCompiler --xml <file.jack | directory>");
        System.out.println("  java JackCompiler --tokens <file.jack | directory>");
        System.out.println();
        System.out.println("Default mode compiles Jack to VM.");
        System.out.println("--xml writes XxxT.xml and Xxx.xml for Project 10.");
        System.out.println("--tokens writes only XxxT.xml.");
    }

    private static List<Path> collectJackFiles(Path input) throws IOException {
        if (Files.isRegularFile(input)) {
            if (!input.toString().endsWith(".jack")) {
                throw new IllegalArgumentException("Input file must end with .jack: " + input);
            }
            return List.of(input);
        }
        if (!Files.isDirectory(input)) {
            throw new IllegalArgumentException("Input path does not exist: " + input);
        }
        try (Stream<Path> stream = Files.list(input)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".jack"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static void compileOne(Path jackFile, Mode mode) throws IOException {
        List<Token> tokens = JackTokenizer.tokenize(jackFile);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("No tokens found in " + jackFile);
        }

        switch (mode) {
            case TOKENS_XML:
                writeTokensXml(jackFile, tokens);
                break;
            case PARSE_XML:
                writeTokensXml(jackFile, tokens);
                writeParseXml(jackFile, tokens);
                break;
            case COMPILE_VM:
                writeVm(jackFile, tokens);
                break;
            default:
                throw new IllegalStateException("Unexpected mode: " + mode);
        }
    }

    private static void writeTokensXml(Path jackFile, List<Token> tokens) throws IOException {
        Path output = replaceExtension(jackFile, "T.xml");
        try (XmlWriter xml = new XmlWriter(output)) {
            xml.open("tokens");
            for (Token token : tokens) {
                xml.leaf(token.xmlTag(), escapeXml(token.value));
            }
            xml.close("tokens");
        }
    }

    private static void writeParseXml(Path jackFile, List<Token> tokens) throws IOException {
        Path output = replaceExtension(jackFile, ".xml");
        try (XmlWriter xml = new XmlWriter(output)) {
            XmlCompilationEngine engine = new XmlCompilationEngine(tokens, xml);
            engine.compileClass();
            engine.ensureFullyConsumed();
        }
    }

    private static void writeVm(Path jackFile, List<Token> tokens) throws IOException {
        Path output = replaceExtension(jackFile, ".vm");
        try (VmWriter writer = new VmWriter(output)) {
            VmCompilationEngine engine = new VmCompilationEngine(tokens, writer);
            engine.compileClass();
            engine.ensureFullyConsumed();
        }
    }

    private static Path replaceExtension(Path input, String replacementSuffix) {
        String name = input.getFileName().toString();
        int idx = name.lastIndexOf('.');
        String base = idx >= 0 ? name.substring(0, idx) : name;
        return input.resolveSibling(base + replacementSuffix);
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private enum Mode {
        COMPILE_VM,
        PARSE_XML,
        TOKENS_XML
    }

    private enum TokenType {
        KEYWORD, SYMBOL, IDENTIFIER, INT_CONST, STRING_CONST
    }

    private enum Kind {
        STATIC, FIELD, ARG, VAR, NONE
    }

    private static final class Token {
        final TokenType type;
        final String value;

        Token(TokenType type, String value) {
            this.type = type;
            this.value = value;
        }

        String xmlTag() {
            switch (type) {
                case KEYWORD: return "keyword";
                case SYMBOL: return "symbol";
                case IDENTIFIER: return "identifier";
                case INT_CONST: return "integerConstant";
                case STRING_CONST: return "stringConstant";
                default: throw new IllegalStateException("Unexpected type: " + type);
            }
        }

        @Override
        public String toString() {
            return type + ":" + value;
        }
    }

    private static final class JackTokenizer {
        private JackTokenizer() {
        }

        static List<Token> tokenize(Path input) throws IOException {
            String source = Files.readString(input, StandardCharsets.UTF_8);
            List<Token> tokens = new ArrayList<>();
            int i = 0;
            while (i < source.length()) {
                char c = source.charAt(i);

                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }

                if (c == '/' && i + 1 < source.length()) {
                    char n = source.charAt(i + 1);
                    if (n == '/') {
                        i += 2;
                        while (i < source.length() && source.charAt(i) != '\n') {
                            i++;
                        }
                        continue;
                    }
                    if (n == '*') {
                        i += 2;
                        while (i + 1 < source.length() && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                            i++;
                        }
                        if (i + 1 >= source.length()) {
                            throw new IllegalArgumentException("Unterminated block comment in " + input);
                        }
                        i += 2;
                        continue;
                    }
                }

                if (c == '"') {
                    int start = ++i;
                    while (i < source.length() && source.charAt(i) != '"') {
                        i++;
                    }
                    if (i >= source.length()) {
                        throw new IllegalArgumentException("Unterminated string constant in " + input);
                    }
                    tokens.add(new Token(TokenType.STRING_CONST, source.substring(start, i)));
                    i++;
                    continue;
                }

                if (SYMBOLS.contains(c)) {
                    tokens.add(new Token(TokenType.SYMBOL, String.valueOf(c)));
                    i++;
                    continue;
                }

                if (Character.isDigit(c)) {
                    int start = i;
                    while (i < source.length() && Character.isDigit(source.charAt(i))) {
                        i++;
                    }
                    tokens.add(new Token(TokenType.INT_CONST, source.substring(start, i)));
                    continue;
                }

                if (Character.isLetter(c) || c == '_') {
                    int start = i;
                    while (i < source.length()) {
                        char t = source.charAt(i);
                        if (Character.isLetterOrDigit(t) || t == '_') {
                            i++;
                        } else {
                            break;
                        }
                    }
                    String word = source.substring(start, i);
                    if (KEYWORDS.contains(word)) {
                        tokens.add(new Token(TokenType.KEYWORD, word));
                    } else {
                        tokens.add(new Token(TokenType.IDENTIFIER, word));
                    }
                    continue;
                }

                throw new IllegalArgumentException("Unexpected character '" + c + "' in " + input);
            }
            return tokens;
        }
    }

    private static final class TokenStream {
        private final List<Token> tokens;
        private int index = 0;

        TokenStream(List<Token> tokens) {
            this.tokens = tokens;
        }

        boolean hasMore() {
            return index < tokens.size();
        }

        Token peek() {
            return peek(0);
        }

        Token peek(int offset) {
            int pos = index + offset;
            if (pos >= tokens.size()) {
                return null;
            }
            return tokens.get(pos);
        }

        Token advance() {
            Token token = peek();
            if (token == null) {
                throw new IllegalStateException("Unexpected end of token stream.");
            }
            index++;
            return token;
        }

        boolean peekValue(String value) {
            Token token = peek();
            return token != null && token.value.equals(value);
        }

        boolean peekType(TokenType type) {
            Token token = peek();
            return token != null && token.type == type;
        }

        Token expectValue(String value) {
            Token token = advance();
            if (!token.value.equals(value)) {
                throw new IllegalArgumentException("Expected '" + value + "' but found " + token);
            }
            return token;
        }

        Token expectType(TokenType type) {
            Token token = advance();
            if (token.type != type) {
                throw new IllegalArgumentException("Expected " + type + " but found " + token);
            }
            return token;
        }

        void ensureFullyConsumed() {
            if (hasMore()) {
                throw new IllegalArgumentException("Unexpected extra token: " + peek());
            }
        }
    }

    private static final class XmlWriter implements AutoCloseable {
        private final BufferedWriter out;
        private int indent = 0;

        XmlWriter(Path path) throws IOException {
            out = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        }

        void open(String tag) throws IOException {
            writeIndent();
            out.write("<" + tag + ">");
            out.newLine();
            indent++;
        }

        void close(String tag) throws IOException {
            indent--;
            writeIndent();
            out.write("</" + tag + ">");
            out.newLine();
        }

        void leaf(String tag, String value) throws IOException {
            writeIndent();
            out.write("<" + tag + "> " + value + " </" + tag + ">");
            out.newLine();
        }

        private void writeIndent() throws IOException {
            for (int i = 0; i < indent; i++) {
                out.write("  ");
            }
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    private static final class XmlCompilationEngine {
        private final TokenStream in;
        private final XmlWriter xml;

        XmlCompilationEngine(List<Token> tokens, XmlWriter xml) {
            this.in = new TokenStream(tokens);
            this.xml = xml;
        }

        void ensureFullyConsumed() {
            in.ensureFullyConsumed();
        }

        void compileClass() throws IOException {
            xml.open("class");
            writeToken(in.expectValue("class"));
            writeToken(in.expectType(TokenType.IDENTIFIER));
            writeToken(in.expectValue("{"));

            while (in.peekValue("static") || in.peekValue("field")) {
                compileClassVarDec();
            }

            while (in.peekValue("constructor") || in.peekValue("function") || in.peekValue("method")) {
                compileSubroutine();
            }

            writeToken(in.expectValue("}"));
            xml.close("class");
        }

        private void compileClassVarDec() throws IOException {
            xml.open("classVarDec");
            writeToken(in.advance());
            compileType();
            writeToken(in.expectType(TokenType.IDENTIFIER));
            while (in.peekValue(",")) {
                writeToken(in.advance());
                writeToken(in.expectType(TokenType.IDENTIFIER));
            }
            writeToken(in.expectValue(";"));
            xml.close("classVarDec");
        }

        private void compileType() throws IOException {
            if (in.peekType(TokenType.IDENTIFIER) ||
                    in.peekValue("int") || in.peekValue("char") || in.peekValue("boolean")) {
                writeToken(in.advance());
            } else {
                throw new IllegalArgumentException("Expected type but found " + in.peek());
            }
        }

        private void compileSubroutine() throws IOException {
            xml.open("subroutineDec");
            writeToken(in.advance());
            if (in.peekValue("void")) {
                writeToken(in.advance());
            } else {
                compileType();
            }
            writeToken(in.expectType(TokenType.IDENTIFIER));
            writeToken(in.expectValue("("));
            compileParameterList();
            writeToken(in.expectValue(")"));
            compileSubroutineBody();
            xml.close("subroutineDec");
        }

        private void compileParameterList() throws IOException {
            xml.open("parameterList");
            if (!in.peekValue(")")) {
                compileType();
                writeToken(in.expectType(TokenType.IDENTIFIER));
                while (in.peekValue(",")) {
                    writeToken(in.advance());
                    compileType();
                    writeToken(in.expectType(TokenType.IDENTIFIER));
                }
            }
            xml.close("parameterList");
        }

        private void compileSubroutineBody() throws IOException {
            xml.open("subroutineBody");
            writeToken(in.expectValue("{"));
            while (in.peekValue("var")) {
                compileVarDec();
            }
            compileStatements();
            writeToken(in.expectValue("}"));
            xml.close("subroutineBody");
        }

        private void compileVarDec() throws IOException {
            xml.open("varDec");
            writeToken(in.expectValue("var"));
            compileType();
            writeToken(in.expectType(TokenType.IDENTIFIER));
            while (in.peekValue(",")) {
                writeToken(in.advance());
                writeToken(in.expectType(TokenType.IDENTIFIER));
            }
            writeToken(in.expectValue(";"));
            xml.close("varDec");
        }

        private void compileStatements() throws IOException {
            xml.open("statements");
            while (true) {
                if (in.peekValue("let")) {
                    compileLet();
                } else if (in.peekValue("if")) {
                    compileIf();
                } else if (in.peekValue("while")) {
                    compileWhile();
                } else if (in.peekValue("do")) {
                    compileDo();
                } else if (in.peekValue("return")) {
                    compileReturn();
                } else {
                    break;
                }
            }
            xml.close("statements");
        }

        private void compileLet() throws IOException {
            xml.open("letStatement");
            writeToken(in.expectValue("let"));
            writeToken(in.expectType(TokenType.IDENTIFIER));
            if (in.peekValue("[")) {
                writeToken(in.advance());
                compileExpression();
                writeToken(in.expectValue("]"));
            }
            writeToken(in.expectValue("="));
            compileExpression();
            writeToken(in.expectValue(";"));
            xml.close("letStatement");
        }

        private void compileIf() throws IOException {
            xml.open("ifStatement");
            writeToken(in.expectValue("if"));
            writeToken(in.expectValue("("));
            compileExpression();
            writeToken(in.expectValue(")"));
            writeToken(in.expectValue("{"));
            compileStatements();
            writeToken(in.expectValue("}"));
            if (in.peekValue("else")) {
                writeToken(in.advance());
                writeToken(in.expectValue("{"));
                compileStatements();
                writeToken(in.expectValue("}"));
            }
            xml.close("ifStatement");
        }

        private void compileWhile() throws IOException {
            xml.open("whileStatement");
            writeToken(in.expectValue("while"));
            writeToken(in.expectValue("("));
            compileExpression();
            writeToken(in.expectValue(")"));
            writeToken(in.expectValue("{"));
            compileStatements();
            writeToken(in.expectValue("}"));
            xml.close("whileStatement");
        }

        private void compileDo() throws IOException {
            xml.open("doStatement");
            writeToken(in.expectValue("do"));
            compileSubroutineCall();
            writeToken(in.expectValue(";"));
            xml.close("doStatement");
        }

        private void compileReturn() throws IOException {
            xml.open("returnStatement");
            writeToken(in.expectValue("return"));
            if (!in.peekValue(";")) {
                compileExpression();
            }
            writeToken(in.expectValue(";"));
            xml.close("returnStatement");
        }

        private void compileExpression() throws IOException {
            xml.open("expression");
            compileTerm();
            while (in.peek() != null && OP_SET.contains(in.peek().value)) {
                writeToken(in.advance());
                compileTerm();
            }
            xml.close("expression");
        }

        private void compileTerm() throws IOException {
            xml.open("term");
            Token token = in.peek();
            if (token == null) {
                throw new IllegalArgumentException("Unexpected end of term.");
            }

            if (token.type == TokenType.INT_CONST || token.type == TokenType.STRING_CONST ||
                    (token.type == TokenType.KEYWORD && KEYWORD_CONSTANTS.contains(token.value))) {
                writeToken(in.advance());
            } else if (token.value.equals("(")) {
                writeToken(in.advance());
                compileExpression();
                writeToken(in.expectValue(")"));
            } else if (UNARY_OP_SET.contains(token.value)) {
                writeToken(in.advance());
                compileTerm();
            } else if (token.type == TokenType.IDENTIFIER) {
                Token next = in.peek(1);
                if (next != null && next.value.equals("[")) {
                    writeToken(in.advance());
                    writeToken(in.advance());
                    compileExpression();
                    writeToken(in.expectValue("]"));
                } else if (next != null && (next.value.equals("(") || next.value.equals("."))) {
                    compileSubroutineCall();
                } else {
                    writeToken(in.advance());
                }
            } else {
                throw new IllegalArgumentException("Unexpected token in term: " + token);
            }
            xml.close("term");
        }

        private void compileSubroutineCall() throws IOException {
            writeToken(in.expectType(TokenType.IDENTIFIER));
            if (in.peekValue(".")) {
                writeToken(in.advance());
                writeToken(in.expectType(TokenType.IDENTIFIER));
            }
            writeToken(in.expectValue("("));
            compileExpressionList();
            writeToken(in.expectValue(")"));
        }

        private void compileExpressionList() throws IOException {
            xml.open("expressionList");
            if (!in.peekValue(")")) {
                compileExpression();
                while (in.peekValue(",")) {
                    writeToken(in.advance());
                    compileExpression();
                }
            }
            xml.close("expressionList");
        }

        private void writeToken(Token token) throws IOException {
            xml.leaf(token.xmlTag(), escapeXml(token.value));
        }
    }

    private static final class SymbolTable {
        private final Map<String, Symbol> classScope = new HashMap<>();
        private final Map<String, Symbol> subroutineScope = new HashMap<>();
        private final Map<Kind, Integer> counters = new HashMap<>();

        SymbolTable() {
            counters.put(Kind.STATIC, 0);
            counters.put(Kind.FIELD, 0);
            counters.put(Kind.ARG, 0);
            counters.put(Kind.VAR, 0);
        }

        void startSubroutine() {
            subroutineScope.clear();
            counters.put(Kind.ARG, 0);
            counters.put(Kind.VAR, 0);
        }

        void define(String name, String type, Kind kind) {
            int index = counters.get(kind);
            counters.put(kind, index + 1);
            Symbol symbol = new Symbol(type, kind, index);
            if (kind == Kind.STATIC || kind == Kind.FIELD) {
                classScope.put(name, symbol);
            } else if (kind == Kind.ARG || kind == Kind.VAR) {
                subroutineScope.put(name, symbol);
            } else {
                throw new IllegalArgumentException("Cannot define symbol with kind NONE");
            }
        }

        int varCount(Kind kind) {
            return counters.get(kind);
        }

        Kind kindOf(String name) {
            Symbol s = resolve(name);
            return s == null ? Kind.NONE : s.kind;
        }

        String typeOf(String name) {
            Symbol s = resolve(name);
            return s == null ? null : s.type;
        }

        int indexOf(String name) {
            Symbol s = resolve(name);
            return s == null ? -1 : s.index;
        }

        private Symbol resolve(String name) {
            Symbol s = subroutineScope.get(name);
            if (s != null) {
                return s;
            }
            return classScope.get(name);
        }

        private static final class Symbol {
            final String type;
            final Kind kind;
            final int index;

            Symbol(String type, Kind kind, int index) {
                this.type = type;
                this.kind = kind;
                this.index = index;
            }
        }
    }

    private static final class VmWriter implements AutoCloseable {
        private final BufferedWriter out;

        VmWriter(Path path) throws IOException {
            out = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        }

        void writePush(String segment, int index) throws IOException {
            writeLine("push " + segment + " " + index);
        }

        void writePop(String segment, int index) throws IOException {
            writeLine("pop " + segment + " " + index);
        }

        void writeArithmetic(String command) throws IOException {
            writeLine(command);
        }

        void writeLabel(String label) throws IOException {
            writeLine("label " + label);
        }

        void writeGoto(String label) throws IOException {
            writeLine("goto " + label);
        }

        void writeIf(String label) throws IOException {
            writeLine("if-goto " + label);
        }

        void writeCall(String name, int nArgs) throws IOException {
            writeLine("call " + name + " " + nArgs);
        }

        void writeFunction(String name, int nLocals) throws IOException {
            writeLine("function " + name + " " + nLocals);
        }

        void writeReturn() throws IOException {
            writeLine("return");
        }

        private void writeLine(String line) throws IOException {
            out.write(line);
            out.newLine();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }

    private static final class VmCompilationEngine {
        private final TokenStream in;
        private final VmWriter vm;
        private final SymbolTable symbols = new SymbolTable();

        private String className;
        private int labelCounter = 0;

        VmCompilationEngine(List<Token> tokens, VmWriter vm) {
            this.in = new TokenStream(tokens);
            this.vm = vm;
        }

        void ensureFullyConsumed() {
            in.ensureFullyConsumed();
        }

        void compileClass() throws IOException {
            in.expectValue("class");
            className = in.expectType(TokenType.IDENTIFIER).value;
            in.expectValue("{");

            while (in.peekValue("static") || in.peekValue("field")) {
                compileClassVarDec();
            }

            while (in.peekValue("constructor") || in.peekValue("function") || in.peekValue("method")) {
                compileSubroutine();
            }

            in.expectValue("}");
        }

        private void compileClassVarDec() {
            Kind kind = in.peekValue("static") ? Kind.STATIC : Kind.FIELD;
            in.advance();
            String type = compileTypeName();
            String name = in.expectType(TokenType.IDENTIFIER).value;
            symbols.define(name, type, kind);
            while (in.peekValue(",")) {
                in.advance();
                name = in.expectType(TokenType.IDENTIFIER).value;
                symbols.define(name, type, kind);
            }
            in.expectValue(";");
        }

        private String compileTypeName() {
            Token token = in.advance();
            if (token.type == TokenType.IDENTIFIER ||
                    token.value.equals("int") || token.value.equals("char") || token.value.equals("boolean")) {
                return token.value;
            }
            throw new IllegalArgumentException("Expected type but found " + token);
        }

        private void compileSubroutine() throws IOException {
            String subroutineType = in.advance().value;
            in.advance();
            String subroutineName = in.expectType(TokenType.IDENTIFIER).value;

            symbols.startSubroutine();
            if (subroutineType.equals("method")) {
                symbols.define("this", className, Kind.ARG);
            }

            in.expectValue("(");
            compileParameterList();
            in.expectValue(")");

            in.expectValue("{");
            while (in.peekValue("var")) {
                compileVarDec();
            }

            int localCount = symbols.varCount(Kind.VAR);
            vm.writeFunction(className + "." + subroutineName, localCount);

            if (subroutineType.equals("constructor")) {
                vm.writePush("constant", symbols.varCount(Kind.FIELD));
                vm.writeCall("Memory.alloc", 1);
                vm.writePop("pointer", 0);
            } else if (subroutineType.equals("method")) {
                vm.writePush("argument", 0);
                vm.writePop("pointer", 0);
            }

            compileStatements();
            in.expectValue("}");
        }

        private void compileParameterList() {
            if (!in.peekValue(")")) {
                String type = compileTypeName();
                String name = in.expectType(TokenType.IDENTIFIER).value;
                symbols.define(name, type, Kind.ARG);
                while (in.peekValue(",")) {
                    in.advance();
                    type = compileTypeName();
                    name = in.expectType(TokenType.IDENTIFIER).value;
                    symbols.define(name, type, Kind.ARG);
                }
            }
        }

        private void compileVarDec() {
            in.expectValue("var");
            String type = compileTypeName();
            String name = in.expectType(TokenType.IDENTIFIER).value;
            symbols.define(name, type, Kind.VAR);
            while (in.peekValue(",")) {
                in.advance();
                name = in.expectType(TokenType.IDENTIFIER).value;
                symbols.define(name, type, Kind.VAR);
            }
            in.expectValue(";");
        }

        private void compileStatements() throws IOException {
            while (true) {
                if (in.peekValue("let")) {
                    compileLet();
                } else if (in.peekValue("if")) {
                    compileIf();
                } else if (in.peekValue("while")) {
                    compileWhile();
                } else if (in.peekValue("do")) {
                    compileDo();
                } else if (in.peekValue("return")) {
                    compileReturn();
                } else {
                    break;
                }
            }
        }

        private void compileLet() throws IOException {
            in.expectValue("let");
            String name = in.expectType(TokenType.IDENTIFIER).value;
            boolean isArray = false;

            if (in.peekValue("[")) {
                isArray = true;
                pushVariable(name);
                in.advance();
                compileExpression();
                in.expectValue("]");
                vm.writeArithmetic("add");
            }

            in.expectValue("=");
            compileExpression();
            in.expectValue(";");

            if (isArray) {
                vm.writePop("temp", 0);
                vm.writePop("pointer", 1);
                vm.writePush("temp", 0);
                vm.writePop("that", 0);
            } else {
                popVariable(name);
            }
        }

        private void compileIf() throws IOException {
            in.expectValue("if");
            String trueLabel = newLabel("IF_TRUE");
            String falseLabel = newLabel("IF_FALSE");
            String endLabel = newLabel("IF_END");

            in.expectValue("(");
            compileExpression();
            in.expectValue(")");

            vm.writeIf(trueLabel);
            vm.writeGoto(falseLabel);
            vm.writeLabel(trueLabel);

            in.expectValue("{");
            compileStatements();
            in.expectValue("}");

            if (in.peekValue("else")) {
                vm.writeGoto(endLabel);
                vm.writeLabel(falseLabel);
                in.advance();
                in.expectValue("{");
                compileStatements();
                in.expectValue("}");
                vm.writeLabel(endLabel);
            } else {
                vm.writeLabel(falseLabel);
            }
        }

        private void compileWhile() throws IOException {
            in.expectValue("while");
            String expLabel = newLabel("WHILE_EXP");
            String endLabel = newLabel("WHILE_END");

            vm.writeLabel(expLabel);
            in.expectValue("(");
            compileExpression();
            in.expectValue(")");
            vm.writeArithmetic("not");
            vm.writeIf(endLabel);

            in.expectValue("{");
            compileStatements();
            in.expectValue("}");

            vm.writeGoto(expLabel);
            vm.writeLabel(endLabel);
        }

        private void compileDo() throws IOException {
            in.expectValue("do");
            compileSubroutineCall();
            in.expectValue(";");
            vm.writePop("temp", 0);
        }

        private void compileReturn() throws IOException {
            in.expectValue("return");
            if (!in.peekValue(";")) {
                compileExpression();
            } else {
                vm.writePush("constant", 0);
            }
            in.expectValue(";");
            vm.writeReturn();
        }

        private void compileExpression() throws IOException {
            compileTerm();
            while (in.peek() != null && OP_SET.contains(in.peek().value)) {
                String op = in.advance().value;
                compileTerm();
                writeBinaryOp(op);
            }
        }

        private void compileTerm() throws IOException {
            Token token = in.peek();
            if (token == null) {
                throw new IllegalArgumentException("Unexpected end of term.");
            }

            switch (token.type) {
                case INT_CONST:
                    in.advance();
                    vm.writePush("constant", Integer.parseInt(token.value));
                    return;
                case STRING_CONST:
                    in.advance();
                    writeStringConstant(token.value);
                    return;
                case KEYWORD:
                    if (!KEYWORD_CONSTANTS.contains(token.value)) {
                        break;
                    }
                    in.advance();
                    writeKeywordConstant(token.value);
                    return;
                case IDENTIFIER:
                    Token next = in.peek(1);
                    if (next != null && next.value.equals("[")) {
                        String name = in.advance().value;
                        pushVariable(name);
                        in.advance();
                        compileExpression();
                        in.expectValue("]");
                        vm.writeArithmetic("add");
                        vm.writePop("pointer", 1);
                        vm.writePush("that", 0);
                        return;
                    }
                    if (next != null && (next.value.equals("(") || next.value.equals("."))) {
                        compileSubroutineCall();
                        return;
                    }
                    String name = in.advance().value;
                    pushVariable(name);
                    return;
                default:
                    break;
            }

            if (token.value.equals("(")) {
                in.advance();
                compileExpression();
                in.expectValue(")");
                return;
            }

            if (UNARY_OP_SET.contains(token.value)) {
                String op = in.advance().value;
                compileTerm();
                if (op.equals("-")) {
                    vm.writeArithmetic("neg");
                } else {
                    vm.writeArithmetic("not");
                }
                return;
            }

            throw new IllegalArgumentException("Unexpected token in term: " + token);
        }

        private int compileExpressionList() throws IOException {
            int count = 0;
            if (!in.peekValue(")")) {
                compileExpression();
                count++;
                while (in.peekValue(",")) {
                    in.advance();
                    compileExpression();
                    count++;
                }
            }
            return count;
        }

        private void compileSubroutineCall() throws IOException {
            String first = in.expectType(TokenType.IDENTIFIER).value;
            int nArgs = 0;
            String callName;

            if (in.peekValue(".")) {
                in.advance();
                String second = in.expectType(TokenType.IDENTIFIER).value;
                Kind kind = symbols.kindOf(first);
                if (kind != Kind.NONE) {
                    pushVariable(first);
                    nArgs = 1;
                    callName = symbols.typeOf(first) + "." + second;
                } else {
                    callName = first + "." + second;
                }
            } else {
                vm.writePush("pointer", 0);
                nArgs = 1;
                callName = className + "." + first;
            }

            in.expectValue("(");
            nArgs += compileExpressionList();
            in.expectValue(")");
            vm.writeCall(callName, nArgs);
        }

        private void writeBinaryOp(String op) throws IOException {
            switch (op) {
                case "+": vm.writeArithmetic("add"); break;
                case "-": vm.writeArithmetic("sub"); break;
                case "*": vm.writeCall("Math.multiply", 2); break;
                case "/": vm.writeCall("Math.divide", 2); break;
                case "&": vm.writeArithmetic("and"); break;
                case "|": vm.writeArithmetic("or"); break;
                case "<": vm.writeArithmetic("lt"); break;
                case ">": vm.writeArithmetic("gt"); break;
                case "=": vm.writeArithmetic("eq"); break;
                default: throw new IllegalArgumentException("Unknown operator: " + op);
            }
        }

        private void writeKeywordConstant(String keyword) throws IOException {
            switch (keyword) {
                case "true":
                    vm.writePush("constant", 0);
                    vm.writeArithmetic("not");
                    break;
                case "false":
                case "null":
                    vm.writePush("constant", 0);
                    break;
                case "this":
                    vm.writePush("pointer", 0);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown keyword constant: " + keyword);
            }
        }

        private void writeStringConstant(String s) throws IOException {
            vm.writePush("constant", s.length());
            vm.writeCall("String.new", 1);
            for (int i = 0; i < s.length(); i++) {
                vm.writePush("constant", s.charAt(i));
                vm.writeCall("String.appendChar", 2);
            }
        }

        private void pushVariable(String name) throws IOException {
            Kind kind = symbols.kindOf(name);
            int index = symbols.indexOf(name);
            if (kind == Kind.NONE || index < 0) {
                throw new IllegalArgumentException("Undefined variable: " + name);
            }
            vm.writePush(segmentOf(kind), index);
        }

        private void popVariable(String name) throws IOException {
            Kind kind = symbols.kindOf(name);
            int index = symbols.indexOf(name);
            if (kind == Kind.NONE || index < 0) {
                throw new IllegalArgumentException("Undefined variable: " + name);
            }
            vm.writePop(segmentOf(kind), index);
        }

        private String segmentOf(Kind kind) {
            switch (kind) {
                case STATIC: return "static";
                case FIELD: return "this";
                case ARG: return "argument";
                case VAR: return "local";
                default: throw new IllegalArgumentException("Unexpected kind: " + kind);
            }
        }

        private String newLabel(String prefix) {
            return className + "$" + prefix + labelCounter++;
        }
    }
}
