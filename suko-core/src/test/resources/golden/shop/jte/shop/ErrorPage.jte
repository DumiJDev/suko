@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param String message
@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<h1>Não foi possível concluir o pedido</h1><p class="${"empty"}">${message}</p><p><a href="${"/"}">Voltar à página inicial</a></p>`)
