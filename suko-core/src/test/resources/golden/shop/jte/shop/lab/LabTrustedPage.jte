@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param String site
@param String target
@param java.util.List<String> sites
@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<h1>Lab de trustedUrl</h1><form class="${"lab-input"}" method="${"get"}" action="${"/lab/trusted"}"><label>Chave
        <input type="${"text"}" name="${"site"}" value="${site}" maxlength="${"500"}"/></label><button type="${"submit"}">Ver</button></form><p>Chave recebida <code class="${"lab-value"}">${site}</code></p><p><a class="${"lab-trusted"}" href="${target}">destino da allowlist</a></p><ul class="${"lab-sites"}">@for(String k : sites)
<li><code>${k}</code></li>
@endfor
</ul>`)
