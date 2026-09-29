package io.suko.lang.project;

import io.suko.lang.ast.Cardinality;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.Param;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.Type;

import java.util.Optional;

/**
 * Assinatura de um parâmetro tal como o {@link ProjectIndex} a guarda: o
 * suficiente para validar chamadas noutros ficheiros e para hover/completion,
 * sem reter o AST do componente. {@code type} é o texto do tipo como foi
 * escrito (slots reconstroem {@code Component}, {@code List<Component>} ou
 * {@code Function<T, Component>} — o AST só guarda a forma resolvida).
 * {@code cardinality}/{@code renderProp} só têm sentido quando {@code slot}.
 */
public record ParamInfo(
    String name,
    String type,
    Optional<String> defaultText,
    boolean slot,
    Optional<Cardinality> cardinality,
    boolean renderProp,
    SourceSpan span
) {

    public static ParamInfo of(Param param) {
        return switch (param) {
            case Param.ValueParam v -> new ParamInfo(v.name(), typeText(v.type()),
                v.defaultValue().map(Expr::pretty), false, Optional.empty(), false, v.span());
            case Param.SlotParam s -> new ParamInfo(s.name(), slotTypeText(s),
                s.defaultValue().map(Expr::pretty), true, Optional.of(s.cardinality()), s.renderProp(), s.span());
        };
    }

    /** Slot obrigatório: sem default e cardinalidade ONE (mesma regra do SemanticChecker). */
    public boolean requiredSlot() {
        return slot && defaultText.isEmpty() && cardinality.orElse(Cardinality.ONE) == Cardinality.ONE;
    }

    static String typeText(Type type) {
        StringBuilder sb = new StringBuilder(type.name());
        if (!type.typeArguments().isEmpty()) {
            sb.append('<');
            for (int i = 0; i < type.typeArguments().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(typeText(type.typeArguments().get(i)));
            }
            sb.append('>');
        }
        sb.append("[]".repeat(type.arrayDimensions()));
        return sb.toString();
    }

    private static String slotTypeText(Param.SlotParam slot) {
        String single = slot.renderProp()
            ? "Function<" + typeText(slot.elementType()) + ", Component>"
            : "Component";
        return slot.cardinality() == Cardinality.MANY ? "List<" + single + ">" : single;
    }
}
