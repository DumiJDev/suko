package io.suko.lang.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/** {@code suko { security { ... } }} */
public class SukoSecurity {
    private final ListProperty<String> urlSchemes;
    private final ListProperty<String> imageDataTypes;
    private final Property<Boolean> strictCsp;
    private final ListProperty<String> codeAttributes;
    private final ListProperty<String> urlAttributes;
    private final Property<Boolean> jtePolicy;
    private final Property<Boolean> allowSchemeRelative;

    @Inject
    public SukoSecurity(ObjectFactory objects) {
        this.urlSchemes = objects.listProperty(String.class);
        this.imageDataTypes = objects.listProperty(String.class);
        this.strictCsp = objects.property(Boolean.class);
        this.codeAttributes = objects.listProperty(String.class);
        this.urlAttributes = objects.listProperty(String.class);
        this.jtePolicy = objects.property(Boolean.class);
        this.allowSchemeRelative = objects.property(Boolean.class);
    }

    public ListProperty<String> getUrlSchemes() { return urlSchemes; }
    public ListProperty<String> getImageDataTypes() { return imageDataTypes; }
    public Property<Boolean> getStrictCsp() { return strictCsp; }
    public ListProperty<String> getCodeAttributes() { return codeAttributes; }
    public ListProperty<String> getUrlAttributes() { return urlAttributes; }
    public Property<Boolean> getJtePolicy() { return jtePolicy; }
    /** {@code true} deixa passar URLs relativos ao esquema ({@code //host}, {@code /\host}); por omissão são bloqueados. */
    public Property<Boolean> getAllowSchemeRelative() { return allowSchemeRelative; }
}
