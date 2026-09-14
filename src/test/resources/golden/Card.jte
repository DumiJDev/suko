@param String title
@param java.util.List<T> items
@param String emptyLabel

<div class="${"card"}"><h2>${title}</h2>@if(items.size() > 0)
<ul>@for(T item : items)
<li>${item.name} — ${((item.price == null ? null : item.price.format()) == null ? "sem preço" : (item.price == null ? null : item.price.format()))}</li>
@endfor
</ul>
@else
<p>${emptyLabel}</p>
@endif
</div>