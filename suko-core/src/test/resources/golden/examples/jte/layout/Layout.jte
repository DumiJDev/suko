@param String title
@param gg.jte.Content header
@param gg.jte.Content sidebar
@param gg.jte.Content footer
@param gg.jte.Content body

<html><head><title>${title}</title><meta charset="${"utf-8"}"/><meta name="${"viewport"}" content="${"width=device-width, initial-scale=1"}"/></head><body class="${"layout"}"><header class="${"layout-header"}">${header}</header><div class="${"layout-container"}"><aside class="${"layout-sidebar"}">${sidebar}</aside><main class="${"layout-main"}">${body}</main></div><footer class="${"layout-footer"}">${footer}</footer></body></html>