@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param gg.jte.Content children

<html lang="${"pt"}"><head><meta charset="${"utf-8"}"/><meta name="${"viewport"}" content="${"width=device-width, initial-scale=1"}"/><title>${title}</title><link rel="${"stylesheet"}" href="${"/css/shop.css"}"/></head><body><header class="${"site-header"}"><a class="${"brand"}" href="${"/"}">Suko Shop</a><nav class="${"site-nav"}">@for(java.util.Map<String, String> c : categories)
<a href="${io.suko.generated.SukoSafe.url("/c/" + (c.get("slug")))}">${c.get("name")}</a>
@endfor
</nav><a class="${"cart-link"}" href="${"/cart"}">Carrinho <span class="${"cart-count"}">${cartCount}</span></a></header><main>${children}</main><footer class="${"site-footer"}">Loja de exemplo do Suko, com dados fictícios.</footer></body></html>