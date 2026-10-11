@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param String s
@param java.util.List<java.util.Map<String, String>> items
@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<h1>Lab de srcset</h1><form class="${"lab-input"}" method="${"get"}" action="${"/lab/srcset"}"><label>srcset
        <input type="${"text"}" name="${"s"}" value="${s}" maxlength="${"500"}"/></label><button type="${"submit"}">Ver</button></form>@if(!s.isEmpty())
<p>Valor recebido <code class="${"lab-value"}">${s}</code></p><img class="${"lab-srcset"}" src="${"/img/produto.svg"}" srcset="${io.suko.generated.SukoSafe.srcset(s)}" alt="${"srcset dinâmico"}" width="${"160"}" height="${"100"}"/><picture><source class="${"lab-source"}" srcset="${io.suko.generated.SukoSafe.srcset(s)}"/><img src="${"/img/produto.svg"}" alt="${"picture com source dinâmico"}" width="${"160"}" height="${"100"}"/></picture><img class="${"lab-img-src"}" src="${io.suko.generated.SukoSafe.url(s)}" alt="${"src dinâmico"}" width="${"160"}" height="${"100"}"/>
@endif
<h2>Valores guardados</h2><ul class="${"lab-items"}">@for(java.util.Map<String, String> i : items)
<li><code>${i.get("value")}</code><img class="${"lab-item-srcset"}" src="${"/img/produto.svg"}" srcset="${io.suko.generated.SukoSafe.srcset(i.get("value"))}" alt="${""}" width="${"16"}" height="${"16"}"/></li>
@endfor
</ul>`)
