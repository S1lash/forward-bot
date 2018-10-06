CREATE TABLE chat_settings
(
  chat_id BIGINT NOT NULL PRIMARY KEY,
  state VARCHAR NOT NULL
);

CREATE TABLE vk_configuration
(
  telegram_chat_id BIGINT NOT NULL PRIMARY KEY,
  token VARCHAR NOT NULL,
  user_id BIGINT NOT NULL
);

CREATE TABLE telegram_vk_map
(
  id BIGINT PRIMARY KEY NOT NULL,
  telegram_chat_id BIGINT NOT NULL,
  vk_user_id BIGINT NOT NULL,
  vk_user_name VARCHAR NOT NULL
);
CREATE UNIQUE INDEX UNIQ_CONV_CONF ON telegram_vk_map (telegram_chat_id, vk_user_id);
CREATE SEQUENCE seq_telegram_vk_map START 1;