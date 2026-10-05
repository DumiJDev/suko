@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param java.util.List<java.util.Map<String, String>> products

@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<h1>Bem-vindo à Suko Shop</h1>@template.shop.SearchBox(q = "")
@template.shop.ProductGrid(products = products)
`)
