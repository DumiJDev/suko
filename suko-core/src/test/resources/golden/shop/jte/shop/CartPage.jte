@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param String csrfName
@param String csrfToken
@param java.util.List<java.util.Map<String, String>> lines
@param String total
@param String totalCents

@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<h1>Carrinho</h1>@if(lines.isEmpty())
<p class="${"empty"}">O carrinho está vazio.</p><p><a href="${"/"}">Ver produtos</a></p>
@else
<ul class="${"cart-lines"}">@for(java.util.Map<String, String> l : lines)
@template.shop.CartLine(line = l, csrfName = csrfName, csrfToken = csrfToken)

@endfor
</ul><p class="${"cart-total"}" data-cents="${totalCents}">Total: ${total}</p><p><a class="${"button"}" href="${"/checkout"}">Finalizar compra</a></p>
@endif
`)
