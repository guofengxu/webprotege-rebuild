package wprebuild.kernel.codec;

import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxParser;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;

/**
 * Parses one functional-syntax expression by calling {@code Axiom()} or {@code Annotation()} on a
 * reused OWL API 5.5.1 parser.
 *
 * <p>Not thread-safe. One decoder owns one instance for a single encode or decode. Package-private
 * parser types are reached by reflection: wp-kernel has no {@code module-info.java} and OWL API is
 * on the classpath, so {@code setAccessible} does not need {@code --add-opens}. Revisit this class
 * if the kernel becomes a JPMS module or OWL API moves off 5.5.1.
 */
final class OfnExpressionParser {

    private static final String INTERNAL_API =
            "OWL API 5.5.1 functional syntax parser internal classes are not available";

    /** Matches the closing parenthesis of the slow path's {@code Ontology(<urn:wp:codec> …)} wrapper. */
    private static final String SENTINEL = " )";

    private static final int CLOSE_PAREN;

    private static final int END_OF_FILE;

    private static final Constructor<?> TOKENIZER;

    private static final Constructor<?> PARSER;

    private static final Method REINIT;

    private static final Method NEXT_TOKEN;

    private static final Field TOKEN_KIND;

    static {
        try {
            Class<?> tokenizerClass = Class.forName("org.semanticweb.owlapi.functional.parser.CustomTokenizer");
            Class<?> tokenManager = Class.forName("org.semanticweb.owlapi.functional.parser.TokenManager");
            Class<?> token = Class.forName("org.semanticweb.owlapi.functional.parser.Token");
            Class<?> constants =
                    Class.forName("org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxParserConstants");
            Class<?> parserClass = Class.forName("org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxParser");
            TOKENIZER = tokenizerClass.getDeclaredConstructor(Reader.class);
            TOKENIZER.setAccessible(true);
            PARSER = parserClass.getConstructor(tokenManager);
            REINIT = parserClass.getMethod("ReInit", tokenManager);
            NEXT_TOKEN = parserClass.getMethod("getNextToken");
            TOKEN_KIND = token.getField("kind");
            TOKEN_KIND.setAccessible(true);
            Field closeParen = constants.getField("CLOSEPAR");
            Field endOfFile = constants.getField("EOF");
            closeParen.setAccessible(true);
            endOfFile.setAccessible(true);
            CLOSE_PAREN = closeParen.getInt(null);
            END_OF_FILE = endOfFile.getInt(null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(INTERNAL_API, ex);
        }
    }

    private final OWLFunctionalSyntaxParser parser;

    OfnExpressionParser(OWLOntology context, OWLOntologyLoaderConfiguration loader) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(loader, "loader");
        try {
            parser = (OWLFunctionalSyntaxParser) PARSER.newInstance(tokenizer(""));
            parser.setUp(context, loader);
        } catch (InvocationTargetException ex) {
            throw internal(ex.getCause() == null ? ex : ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw internal(ex);
        }
    }

    Optional<OWLAxiom> axiom(String ofn) {
        return read(ofn, parser::Axiom);
    }

    Optional<OWLAnnotation> annotation(String ofn) {
        return read(ofn, parser::Annotation);
    }

    private <T> Optional<T> read(String ofn, Supplier<T> production) {
        Objects.requireNonNull(ofn, "ofn");
        reinit(ofn + SENTINEL);
        T value;
        try {
            value = production.get();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        if (value == null || !closed()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    private void reinit(String text) {
        try {
            REINIT.invoke(parser, tokenizer(text));
        } catch (InvocationTargetException ex) {
            throw internal(ex.getCause() == null ? ex : ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw internal(ex);
        }
    }

    private boolean closed() {
        try {
            return kind(NEXT_TOKEN.invoke(parser)) == CLOSE_PAREN && kind(NEXT_TOKEN.invoke(parser)) == END_OF_FILE;
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            if (cause instanceof RuntimeException) {
                return false;
            }
            throw internal(cause == null ? ex : cause);
        } catch (ReflectiveOperationException ex) {
            throw internal(ex);
        }
    }

    private static int kind(Object token) throws ReflectiveOperationException {
        return TOKEN_KIND.getInt(token);
    }

    private static Object tokenizer(String text) throws ReflectiveOperationException, InvocationTargetException {
        return TOKENIZER.newInstance(new StringReader(text));
    }

    private static IllegalStateException internal(Throwable cause) {
        return new IllegalStateException(INTERNAL_API, cause);
    }
}
