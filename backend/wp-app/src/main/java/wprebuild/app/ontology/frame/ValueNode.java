package wprebuild.app.ontology.frame;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** An annotation or restriction filler. {@code type} is {@code iri} or {@code literal}. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = IriValue.class, name = "iri"),
    @JsonSubTypes.Type(value = LiteralValue.class, name = "literal")
})
public sealed interface ValueNode permits IriValue, LiteralValue {}
