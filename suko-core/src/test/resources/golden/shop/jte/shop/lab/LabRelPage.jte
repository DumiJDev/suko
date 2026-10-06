@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param String rel
@param String t
@param String p
@param java.util.List<java.util.Map<String, String>> items
@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<h1>Lab de rel e ping</h1><form class="${"lab-input"}" method="${"get"}" action="${"/lab/rel"}"><label>rel
        <input type="${"text"}" name="${"rel"}" value="${rel}" maxlength="${"500"}"/></label><label>target
        <input type="${"text"}" name="${"t"}" value="${t}" maxlength="${"500"}"/></label><label>ping
        <input type="${"text"}" name="${"p"}" value="${p}" maxlength="${"500"}"/></label><button type="${"submit"}">Ver</button></form><p><a class="${"lab-rel"}" href="${"https://example.com/"}" target="${"_blank"}" rel="${io.suko.generated.SukoSafe.rel(rel)}">target blank com rel dinâmico</a></p><p><a class="${"lab-rel-target"}" href="${"https://example.com/"}" target="${t}" rel="${io.suko.generated.SukoSafe.rel(rel)}">target e rel dinâmicos</a></p><p><a class="${"lab-target"}" href="${"https://example.com/"}" target="${t}" rel="${"noopener"}">target dinâmico sem rel</a></p><p><a class="${"lab-ping"}" href="${"/lab/rel"}" ping="${io.suko.generated.SukoSafe.ping(p)}">link com ping dinâmico</a></p><h2>Valores guardados</h2><ul class="${"lab-items"}">@for(java.util.Map<String, String> i : items)
<li><a class="${"lab-item-rel"}" href="${"https://example.com/"}" target="${"_blank"}" rel="${io.suko.generated.SukoSafe.rel(i.get("value"))}">rel guardado</a><a class="${"lab-item-ping"}" href="${"/lab/rel"}" ping="${io.suko.generated.SukoSafe.ping(i.get("value"))}">ping guardado</a></li>
@endfor
</ul>`)
