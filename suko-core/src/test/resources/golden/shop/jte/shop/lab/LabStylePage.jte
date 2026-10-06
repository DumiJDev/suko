@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param String pct
@param String w
@param java.util.List<java.util.Map<String, String>> items
@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<h1>Lab de style</h1><form class="${"lab-input"}" method="${"get"}" action="${"/lab/style"}"><label>Percentagem
        <input type="${"text"}" name="${"pct"}" value="${pct}" maxlength="${"500"}"/></label><label>Largura
        <input type="${"text"}" name="${"w"}" value="${w}" maxlength="${"500"}"/></label><button type="${"submit"}">Ver</button></form><p>Valores recebidos <code class="${"lab-value"}">${pct}</code> e <code class="${"lab-value"}">${w}</code></p><div class="${"lab-pct"}" style="${"--pct: " + io.suko.generated.SukoSafe.cssValue(pct) + "%"}">barra</div><div class="${"lab-width"}" style="${"width: " + io.suko.generated.SukoSafe.cssValue(w) + ""}">largura</div><h2>Valores guardados</h2><ul class="${"lab-items"}">@for(java.util.Map<String, String> i : items)
<li class="${"lab-item-style"}" style="${"width: " + io.suko.generated.SukoSafe.cssValue(i.get("value")) + ""}"><code>${i.get("value")}</code></li>
@endfor
</ul>`)
