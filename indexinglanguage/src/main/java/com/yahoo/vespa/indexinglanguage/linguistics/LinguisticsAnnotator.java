// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.indexinglanguage.linguistics;

import com.yahoo.document.annotation.Annotation;
import com.yahoo.document.annotation.AnnotationTypes;
import com.yahoo.document.annotation.Span;
import com.yahoo.document.annotation.SpanList;
import com.yahoo.document.annotation.SpanTree;
import com.yahoo.document.annotation.SpanTrees;
import com.yahoo.document.datatypes.StringFieldValue;
import com.yahoo.language.Linguistics;
import com.yahoo.language.process.StemMode;
import com.yahoo.language.process.Token;
import com.yahoo.language.process.Tokenizer;
import com.yahoo.text.Text;

import java.util.HashMap;
import java.util.Map;

import static com.yahoo.language.LinguisticsCase.toLowerCase;

/**
 * This is a tool for adding {@link AnnotationTypes} type annotations to {@link StringFieldValue} objects.
 *
 * @author Simon Thoresen Hult
 */
public class LinguisticsAnnotator {

    private final Linguistics factory;
    private final AnnotatorConfig config;

    private static class TermOccurrences {

        final Map<String, Integer> termOccurrences = new HashMap<>();
        final int maxOccurrences;

        public TermOccurrences(int maxOccurrences) {
            this.maxOccurrences = maxOccurrences;
        }

        boolean termCountBelowLimit(String term) {
            String lowerCasedTerm = toLowerCase(term);
            int occurrences = termOccurrences.getOrDefault(lowerCasedTerm, 0);
            if (occurrences >= maxOccurrences) return false;

            termOccurrences.put(lowerCasedTerm, occurrences + 1);
            return true;
        }

    }

    /**
     * Constructs a new instance of this annotator.
     *
     * @param factory the linguistics factory to use when annotating
     * @param config  the linguistics config to use
     */
    public LinguisticsAnnotator(Linguistics factory, AnnotatorConfig config) {
        this.factory = factory;
        this.config = config;
    }

    /**
     * Annotates the given string with the appropriate linguistics annotations.
     *
     * @param text the text to annotate
     * @return whether anything was annotated
     */
    public boolean annotate(StringFieldValue text) {
        if (text.getSpanTree(SpanTrees.LINGUISTICS) != null) return true;  // Already annotated with LINGUISTICS.

        Tokenizer tokenizer = factory.getTokenizer();
        String input = (text.getString().length() <= config.getMaxTokenizeLength())
                ? text.getString()
                : Text.substringByCodepoints(text.getString(), 0, config.getMaxTokenizeLength());
        Iterable<Token> tokens = tokenizer.tokenize(input, config.getLanguage(), config.getStemMode(),
                                                    config.getRemoveAccents());
        TermOccurrences termOccurrences = new TermOccurrences(config.getMaxTermOccurrences());
        SpanTree tree = new SpanTree(SpanTrees.LINGUISTICS);
        for (Token token : tokens)
            addAnnotationSpan(text.getString(), tree.spanList(), token, config.getStemMode(), termOccurrences);

        if (tree.numAnnotations() == 0) return false;
        text.setSpanTree(tree);
        return true;
    }

    /**
     * Creates a TERM annotation which has the term as annotation (only) if it is different from the
     * original.
     *
     * @param term the term
     * @param origTerm the original term
     * @return the created TERM annotation
     */
    public static Annotation termAnnotation(String term, String origTerm) {
        if (term.equals(origTerm))
            return new Annotation(AnnotationTypes.TERM);
        else
            return new Annotation(AnnotationTypes.TERM, new StringFieldValue(term));
    }

    private static void addAnnotation(Span here, String term, String orig, TermOccurrences termOccurrences) {
        if (termOccurrences.termCountBelowLimit(term)) {
            here.annotate(termAnnotation(term, orig));
        }
    }

    private static void addAnnotationSpan(String input, SpanList parent, Token token, StemMode mode, TermOccurrences termOccurrences) {
        if ( ! token.isSpecialToken()) {
            if (token.getNumComponents() > 0) {
                for (int i = 0; i < token.getNumComponents(); ++i) {
                    addAnnotationSpan(input, parent, token.getComponent(i), mode, termOccurrences);
                }
                return;
            }
            if ( ! token.isIndexable()) return;
        }
        if (token.getOffset() >= input.length()) {
            throw new IllegalArgumentException(token + " has offset " + token.getOffset() + ", which is outside the " +
                                               "bounds of the input string '" + input + "'");
        }
        if (token.getOffset() + token.getOrig().length() > input.length()) {
            throw new IllegalArgumentException(token + " has offset " + token.getOffset() + ", which makes it overflow " +
                                               "the bounds of the input string; " + input);
        }
        if (mode == StemMode.ALL) {
            Span where = parent.span((int)token.getOffset(), token.getOrig().length());

            String lowercasedOrig = toLowerCase(token.getOrig());
            String term = token.getTokenString();
            if (term != null) {
                addAnnotation(where, term, token.getOrig(), termOccurrences);
                if ( ! term.equals(lowercasedOrig))
                    addAnnotation(where, token.getOrig(), token.getOrig(), termOccurrences);
            }
            for (int i = 0; i < token.getNumStems(); i++) {
                String stem = token.getStem(i);
                if (! (stem.equals(lowercasedOrig) || stem.equals(term)))
                    addAnnotation(where, stem, token.getOrig(), termOccurrences);
            }
        } else {
            String term = token.getTokenString();
            if (term == null || term.trim().isEmpty()) return;
            if (termOccurrences.termCountBelowLimit(term))  {
                parent.span((int)token.getOffset(), token.getOrig().length()).annotate(termAnnotation(term, token.getOrig()));
            }
        }
    }

}
