create table category (
    id    bigint primary key,
    slug  varchar(40)  not null unique,
    name  varchar(80)  not null
);

create table product (
    id           bigint primary key,
    category_id  bigint        not null references category(id),
    name         varchar(120)  not null,
    description  varchar(2000) not null,
    price_cents  int           not null check (price_cents >= 0),
    image_url    varchar(200)  not null,
    stock        int           not null check (stock >= 0)
);

create table review (
    id          bigint auto_increment primary key,
    product_id  bigint        not null references product(id),
    author      varchar(80)   not null,
    body        varchar(2000) not null,
    rating      int           not null check (rating between 1 and 5),
    website     varchar(200),
    created_at  timestamp     default current_timestamp not null
);

create table orders (
    id           bigint auto_increment primary key,
    name         varchar(120) not null,
    email        varchar(200) not null,
    address      varchar(400) not null,
    total_cents  int          not null,
    created_at   timestamp    default current_timestamp not null
);

create table order_line (
    order_id     bigint not null references orders(id),
    product_id   bigint not null references product(id),
    quantity     int    not null check (quantity between 1 and 20),
    price_cents  int    not null,
    primary key (order_id, product_id)
);
