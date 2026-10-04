@param String variant = "default"
@param gg.jte.Content children

@if(variant.equals("success"))
<span class="${"inline-flex items-center rounded-md bg-green-50 px-2 py-1 text-xs font-medium text-green-700 ring-1 ring-inset ring-green-600/20"}">${children}</span>
@elseif(variant.equals("warning"))
<span class="${"inline-flex items-center rounded-md bg-yellow-50 px-2 py-1 text-xs font-medium text-yellow-800 ring-1 ring-inset ring-yellow-600/20"}">${children}</span>
@elseif(variant.equals("danger"))
<span class="${"inline-flex items-center rounded-md bg-red-50 px-2 py-1 text-xs font-medium text-red-700 ring-1 ring-inset ring-red-600/10"}">${children}</span>
@else
<span class="${"inline-flex items-center rounded-md bg-gray-50 px-2 py-1 text-xs font-medium text-gray-600 ring-1 ring-inset ring-gray-500/10"}">${children}</span>
@endif
