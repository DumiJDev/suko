insert into category (id, slug, name) values
    (1, 'livros', 'Livros'),
    (2, 'cafe',   'Café'),
    (3, 'casa',   'Casa');

-- O produto 1 traz markup, & e aspas na descrição: a página tem de o mostrar como texto.
insert into product (id, category_id, name, description, price_cents, image_url, stock) values
    (1,  1, 'Manual de HTML seguro',      'Um guia prático sobre escape & contexto: "aspas", ''plicas'' e o texto literal <b>negrito?</b> que nunca deve virar negrito.', 2450, '/img/produto.svg', 15),
    (2,  1, 'Contos do Atlântico',        'Doze contos curtos passados entre Lisboa, os Açores e Cabo Verde.', 1690, '/img/produto.svg', 8),
    (3,  1, 'Receitas da avó',            'Pratos de sempre da cozinha portuguesa, explicados passo a passo.', 1999, '/img/produto.svg', 20),
    (4,  1, 'Atlas das aves de Portugal', 'Mais de trezentas espécies ilustradas, com mapas de distribuição.', 3200, '/img/produto.svg', 5),
    (5,  2, 'Café de Timor em grão',      'Torra média, notas de chocolate e frutos secos. Embalagem de 250 g.', 890, '/img/produto.svg', 40),
    (6,  2, 'Café moído da Etiópia',      'Torra clara, floral e cítrico. Ideal para filtro.', 950, '/img/produto.svg', 30),
    (7,  2, 'Cafeteira italiana',         'Cafeteira de alumínio para seis chávenas.', 2750, '/img/produto.svg', 12),
    (8,  2, 'Chávenas de porcelana',      'Conjunto de quatro chávenas com pires, feitas em Coimbra.', 3400, '/img/produto.svg', 6),
    (9,  3, 'Manta de lã de Mira de Aire', 'Manta tecida à mão, 100% lã, em tons de cinza.', 6900, '/img/produto.svg', 4),
    (10, 3, 'Azulejo decorativo',         'Azulejo pintado à mão com padrão tradicional azul e branco.', 1250, '/img/produto.svg', 25),
    (11, 3, 'Vela de cera de abelha',     'Vela artesanal com aroma suave a mel, dura cerca de 40 horas.', 750, '/img/produto.svg', 50),
    (12, 3, 'Cesto de vime',              'Cesto entrançado à mão, útil para mantas e revistas.', 2100, '/img/produto.svg', 9);

insert into review (product_id, author, body, rating, website) values
    (1, 'Ana',    'Muito claro e direto. Recomendo a quem escreve templates.', 5, 'https://exemplo.pt/ana'),
    (5, 'Bruno',  'Ótimo café, chegou bem embalado.', 4, null),
    (7, 'Carla',  'Faz um café excelente, mas a pega aquece um pouco.', 4, null),
    (9, 'Duarte', 'Quentinha e muito bonita. Vale o preço.', 5, null);
