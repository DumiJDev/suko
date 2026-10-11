@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param String u
@param java.util.List<java.util.Map<String, String>> items
@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<h1>Lab de links</h1><form class="${"lab-input"}" method="${"get"}" action="${"/lab/links"}"><label>URL
        <input type="${"text"}" name="${"u"}" value="${u}" maxlength="${"500"}"/></label><button type="${"submit"}">Ver</button></form>@if(!u.isEmpty())
<p>Valor recebido <code class="${"lab-value"}">${u}</code></p><p><a class="${"lab-href"}" href="${io.suko.generated.SukoSafe.url(u)}">href dinâmico</a></p><p><img class="${"lab-src"}" src="${io.suko.generated.SukoSafe.url(u)}" alt="${"src dinâmico"}" width="${"32"}" height="${"32"}"/></p><form class="${"lab-action"}" method="${"get"}" action="${io.suko.generated.SukoSafe.url(u)}"><button class="${"lab-formaction"}" type="${"submit"}" formaction="${io.suko.generated.SukoSafe.url(u)}">formaction dinâmico</button></form>
@endif
<h2>Valores guardados</h2><ul class="${"lab-items"}">@for(java.util.Map<String, String> i : items)
<li><a class="${"lab-item"}" href="${io.suko.generated.SukoSafe.url(i.get("value"))}">${i.get("value")}</a><img class="${"lab-item-src"}" src="${io.suko.generated.SukoSafe.url(i.get("value"))}" alt="${""}" width="${"16"}" height="${"16"}"/></li>
@endfor
</ul>`)
