@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param String q
@param java.util.List<java.util.Map<String, String>> products
@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<h1>Pesquisa</h1>@template.shop.SearchBox(q = q)
@if(q.isEmpty())
<p class="${"empty"}">Escreva o nome de um produto para pesquisar.</p>
@else
@if(products.isEmpty())
<p class="${"empty"}">Nenhum produto encontrado para ${q}.</p>
@else
<p class="${"results"}">Resultados para ${q}</p>@template.shop.ProductGrid(products = products)

@endif

@endif
`)
