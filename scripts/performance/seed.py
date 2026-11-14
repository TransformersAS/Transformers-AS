"""Fixtures exclusivas para una base NUEVA migrada. No reutiliza/reseteea BD ajena."""
import random
import struct
import zlib
from pathlib import Path

PASSWORD = 'Cu08RealTestPassword!'  # Credencial pública de fixture, nunca producción.
HASH = '$2y$12$1b8lQufQ.HVnswuPxAClJ.mZciYOAGr8F2XGKjzndmAJQ7t94AK7m'
DETAILS = [(1, 1, 1, 1), (2, 3, 3, 5), (3, 5, 5, 20)]


def png(size):
    rng = random.Random(size)
    raw = b''.join(b'\0' + rng.randbytes(size * 3) for _ in range(size))
    def chunk(name, value):
        return struct.pack('!I', len(value)) + name + value + struct.pack('!I', zlib.crc32(name + value))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', size, size, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))


def assets(directory):
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    for name, size in [('small', 32), ('medium', 128), ('large', 256)]:
        (directory / (name + '.png')).write_bytes(png(size))


def sql():
    statements = ['START TRANSACTION;']
    for i in range(1, 101):
        statements += [
            f"INSERT INTO user_accounts(id,email,password_hash,status,email_verified_at) VALUES({i},'perf{i:03}@example.test','{HASH}','ACTIVA',CURRENT_TIMESTAMP(6));",
            f"INSERT INTO user_account_roles VALUES({i},'COMPRADOR');",
            f"INSERT INTO account_profiles VALUES({i},'Comprador de carga {i}','2000-01-01','3000000000');",
            f"INSERT INTO account_profile_settings(account_id) VALUES({i});",
            f"INSERT INTO carts(account_id) VALUES({i});",
            f"INSERT INTO addresses(id,account_id,recipient_name,street,city,department,postal_code,phone) VALUES({i},{i},'Fixture {i}','Calle ficticia','Bogotá','Cundinamarca','110111','3000000000');",
        ]
    for i in range(1, 1001):
        statements.append(f"INSERT INTO products(id,name,description,price,stock,category,active,store_id,status) VALUES({i},'Producto de carga {i:04}','Descripción sintética de lectura',{10000+i},1000,'Pruebas',TRUE,1,'ACTIVE');")
        image_count = next((images for pid, images, _, _ in DETAILS if pid == i), 1)
        for pos in range(image_count):
            name = ['small', 'medium', 'large'][pos % 3]
            statements.append(f"INSERT INTO product_images VALUES({i},{pos},'/performance/{name}.png');")
    n = 0
    for product, images, variants, reviews in DETAILS:
        for pos in range(variants):
            statements.append(f"INSERT INTO product_variants VALUES({product},{pos},'Variante {pos+1}',15000,1000);")
        for index in range(reviews):
            n += 1
            buyer = index + 1
            statements += [
                f"INSERT INTO orders(id,account_id,status,payment_status,total,address_id,store_id,shipping_method,transaction_id,created_at,delivery_recipient_name,delivery_street,delivery_city,delivery_department,delivery_phone) VALUES({n},{buyer},'DELIVERED','APPROVED',15000,{buyer},1,'STANDARD','perf-synthetic-{n}',CURRENT_TIMESTAMP(6),'Fixture','Calle ficticia','Bogotá','Cundinamarca','3000000000');",
                f"INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,subtotal) VALUES({n},{product},'Producto de carga',1,15000,15000);",
                f"INSERT INTO product_reviews(account_id,order_id,product_id,store_id,rating,comment) VALUES({buyer},{n},{product},1,5,'Reseña sintética de lectura');",
            ]
    statements.append('COMMIT;')
    return '\n'.join(statements)
