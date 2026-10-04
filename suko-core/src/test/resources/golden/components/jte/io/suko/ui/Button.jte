@param String label
@param String variant = "primary"
@param boolean disabled = false

@if(variant.equals("secondary"))
<button type="${"button"}" class="${"inline-flex items-center justify-center rounded-md bg-white px-4 py-2 text-sm font-semibold text-gray-900 shadow-sm ring-1 ring-inset ring-gray-300 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"}" disabled="${disabled}">${label}</button>
@elseif(variant.equals("ghost"))
<button type="${"button"}" class="${"inline-flex items-center justify-center rounded-md bg-transparent px-4 py-2 text-sm font-semibold text-gray-900 hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-50"}" disabled="${disabled}">${label}</button>
@else
<button type="${"button"}" class="${"inline-flex items-center justify-center rounded-md bg-blue-600 px-4 py-2 text-sm font-semibold text-white shadow-sm hover:bg-blue-500 disabled:cursor-not-allowed disabled:opacity-50"}" disabled="${disabled}">${label}</button>
@endif
