@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param String csrfName
@param String csrfToken
@param java.util.Map<String, String> product
@param java.util.List<java.util.Map<String, String>> reviews
@param java.util.Map<String, String> form
@param java.util.Map<String, String> errors
@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<article class="${"product"}"><img src="${io.suko.generated.SukoSafe.url(product.get("imageUrl"))}" alt="${product.get("name")}" width="${"320"}" height="${"200"}"/><div><h1>${product.get("name")}</h1><p class="${"price"}">${product.get("price")}</p><p class="${"description"}">${product.get("description")}</p><p class="${"stock"}">Em stock: ${product.get("stock")}</p><form class="${"add-to-cart"}" method="${"post"}" action="${"/cart/add"}"><input type="${"hidden"}" name="${csrfName}" value="${csrfToken}"/><input type="${"hidden"}" name="${"id"}" value="${product.get("id")}"/><label>Quantidade
            <input type="${"number"}" name="${"quantity"}" value="${"1"}" min="${"1"}" max="${"20"}"/></label><button type="${"submit"}">Adicionar ao carrinho</button></form><p><a href="${io.suko.generated.SukoSafe.url("/c/" + (product.get("categorySlug")))}">Ver mais produtos desta categoria</a></p></div></article><section class="${"reviews-section"}"><h2>Avaliações</h2>@template.shop.ReviewList(reviews = reviews)
@template.shop.ReviewForm(productId = product.get("id"), csrfName = csrfName, csrfToken = csrfToken, form = form, errors = errors)
</section>`)
