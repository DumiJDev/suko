@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param String csrfName
@param String csrfToken
@param java.util.List<java.util.Map<String, String>> lines
@param String total
@param String totalCents
@param java.util.Map<String, String> form
@param java.util.Map<String, String> errors
@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<h1>Finalizar compra</h1>@if(errors.containsKey("general"))
<p class="${"error"}">${errors.get("general")}</p>
@endif
@if(lines.isEmpty())
<p class="${"empty"}">O carrinho está vazio.</p><p><a href="${"/"}">Ver produtos</a></p>
@else
<ul class="${"checkout-lines"}">@for(java.util.Map<String, String> l : lines)
<li>${l.get("name")}: ${l.get("quantity")} x ${l.get("unitPrice")} = ${l.get("lineTotal")}</li>
@endfor
</ul><p class="${"cart-total"}" data-cents="${totalCents}">Total: ${total}</p>@template.shop.CheckoutForm(csrfName = csrfName, csrfToken = csrfToken, form = form, errors = errors)

@endif
`)
