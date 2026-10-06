@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param String csrfName
@param String csrfToken
@param java.util.List<java.util.Map<String, String>> items
@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<h1>Lab de segurança</h1><p class="${"lab-warning"}">Páginas para o pentest das proteções do Suko. Nunca ligar o perfil lab em produção.</p><ul class="${"lab-pages"}"><li><a class="${"lab-page"}" href="${"/lab/links"}">links, com href, src, action e formaction dinâmicos</a></li><li><a class="${"lab-page"}" href="${"/lab/srcset"}">srcset em img, source e link preload</a></li><li><a class="${"lab-page"}" href="${"/lab/style"}">style com valores por declaração</a></li><li><a class="${"lab-page"}" href="${"/lab/embed"}">iframe de origem constante</a></li><li><a class="${"lab-page"}" href="${"/lab/rel"}">rel com target e ping</a></li><li><a class="${"lab-page"}" href="${"/lab/trusted"}">trustedUrl com allowlist do servidor</a></li></ul><h2>Valores guardados</h2><ol class="${"lab-items"}">@for(java.util.Map<String, String> i : items)
<li><code class="${"lab-item-value"}">${i.get("value")}</code></li>
@endfor
</ol><form class="${"lab-add"}" method="${"post"}" action="${"/lab/items"}"><input type="${"hidden"}" name="${csrfName}" value="${csrfToken}"/><label>Novo valor, até 500 caracteres
        <input type="${"text"}" name="${"value"}" maxlength="${"500"}"/></label><button type="${"submit"}">Guardar</button></form>`)
