@param String variant = "info"
@param gg.jte.Content children

@if(variant.equals("success"))
<div class="${"rounded-md bg-green-50 p-4 text-sm text-green-700 ring-1 ring-inset ring-green-600/20"}">${children}</div>
@elseif(variant.equals("warning"))
<div class="${"rounded-md bg-yellow-50 p-4 text-sm text-yellow-800 ring-1 ring-inset ring-yellow-600/20"}">${children}</div>
@elseif(variant.equals("danger"))
<div class="${"rounded-md bg-red-50 p-4 text-sm text-red-700 ring-1 ring-inset ring-red-600/10"}">${children}</div>
@else
<div class="${"rounded-md bg-blue-50 p-4 text-sm text-blue-700 ring-1 ring-inset ring-blue-600/20"}">${children}</div>
@endif
