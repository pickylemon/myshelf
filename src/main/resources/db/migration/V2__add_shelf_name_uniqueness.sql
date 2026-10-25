alter table shelves
    add constraint uk_shelves_name_per_user
        unique (user_id, name);

drop index idx_shelves_user_id;