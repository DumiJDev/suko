@param String title
@param java.util.List<String> items
@param String emptyLabel = "Sem itens"

<div class="${"card"}"><h2>${title}</h2>@if(items.size() > 0)
<ul>@for(String item : items)
<li>${item}</li>
@endfor
</ul>
@else
<p>${emptyLabel}</p>
@endif
</div>