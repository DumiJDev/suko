@param gg.jte.Content children
@param gg.jte.Content header = null
@param gg.jte.Content footer = null

<div class="${"overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm"}">@if(header != null)
<div class="${"border-b border-gray-200 px-4 py-3 text-sm font-semibold text-gray-900"}">${header}</div>
@endif
<div class="${"px-4 py-3 text-sm text-gray-700"}">${children}</div>@if(footer != null)
<div class="${"border-t border-gray-200 bg-gray-50 px-4 py-3 text-sm text-gray-500"}">${footer}</div>
@endif
</div>