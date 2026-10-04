@param String id
@param String title
@param gg.jte.Content children

<div id="${"dialog-" + (id)}" x-data="${"{ open: false }"}" x-show="${"open"}" class="${"fixed inset-0 z-50 flex items-center justify-center bg-black/50"}"><div class="${"w-full max-w-md rounded-lg bg-white p-6 shadow-xl"}"><h2 class="${"text-lg font-semibold text-gray-900"}">${title}</h2><div class="${"mt-2 text-sm text-gray-600"}">${children}</div></div></div>