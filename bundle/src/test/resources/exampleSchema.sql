drop table if exists example;
create table example
(
    id       bigint       not null,
    name     varchar(255) not null,
    optional varchar(255) null,
    primary key (id)
);
