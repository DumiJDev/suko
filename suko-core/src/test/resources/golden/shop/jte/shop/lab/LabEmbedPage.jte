@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param String v
@param java.util.List<java.util.Map<String, String>> items
@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<h1>Lab de embed</h1><form class="${"lab-input"}" method="${"get"}" action="${"/lab/embed"}"><label>Id do vídeo
        <input type="${"text"}" name="${"v"}" value="${v}" maxlength="${"500"}"/></label><button type="${"submit"}">Ver</button></form><p>Valor recebido <code class="${"lab-value"}">${v}</code></p><iframe class="${"lab-embed"}" src="${"https://www.youtube.com/embed/" + io.suko.generated.SukoSafe.pathSegment(v) + ""}" title="${"vídeo"}" width="${"320"}" height="${"180"}"></iframe><h2>Valores guardados</h2><ul class="${"lab-items"}">@for(java.util.Map<String, String> i : items)
<li><code>${i.get("value")}</code><iframe class="${"lab-item-embed"}" src="${"https://www.youtube.com/embed/" + io.suko.generated.SukoSafe.pathSegment(i.get("value")) + ""}" title="${"vídeo"}" width="${"80"}" height="${"45"}"></iframe></li>
@endfor
</ul>`)
