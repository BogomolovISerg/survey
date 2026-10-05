-- Код подарка (QR/DataMatrix маркировки), отсканированный сотрудником при выдаче.
alter table response   add column gift_item_code varchar(512);
alter table gift_award add column item_code      varchar(512);
