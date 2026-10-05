@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param java.util.Map<String, String> order
@param java.util.List<java.util.Map<String, String>> lines

@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<h1>Encomenda confirmada</h1><p class="${"thanks"}">Obrigado, ${order.get("name")}. A encomenda número ${order.get("id")} foi registada.</p><p class="${"email"}">Confirmação para: ${order.get("email")}</p><p class="${"address"}">Entrega em: ${order.get("address")}</p><ul class="${"order-lines"}">@for(java.util.Map<String, String> l : lines)
<li>${l.get("name")}: ${l.get("quantity")} unidades, ${l.get("lineTotal")}</li>
@endfor
</ul><p class="${"order-total"}" data-cents="${order.get("totalCents")}">Total: ${order.get("total")}</p><p><a href="${"/"}">Continuar a comprar</a></p>`)
