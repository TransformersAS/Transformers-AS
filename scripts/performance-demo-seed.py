#!/usr/bin/env python3
"""SOLO desarrollo/performance. Generador SQL por lotes para V1..V30, MySQL 8.4.
No usa dependencias Python externas, no cambia Flyway ni llama servicios externos.
"""
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

MANIFEST = "performance_demo_seed_manifest"
TABLES = ["categories", "user_accounts", "user_account_roles", "seller_terms_acceptances",
          "stores", "store_shipping_methods", "products", "product_images", "addresses", "orders",
          "order_items", "order_status_history"]


def number(name, minimum, maximum):
    value = os.environ[name]
    if not re.fullmatch(r"[0-9]{1,7}", value) or not minimum <= int(value) <= maximum:
        raise ValueError(f"{name}: debe estar entre {minimum} y {maximum}")
    return int(value)


def literal(value):
    # Hex UTF-8: ni comillas ni barras de entrada pueden convertirse en SQL.
    if value is None:
        return "NULL"
    if isinstance(value, int):
        return str(value)
    return "CONVERT(X'" + str(value).encode().hex() + "' USING utf8mb4)"


def identifier(value):
    return "`" + value.replace("`", "``") + "`"


def main():
    users = number("DEMO_USERS", 1, 10000)
    products = number("DEMO_PRODUCTS", 5, 100000)
    orders = number("DEMO_ORDERS", 0, 100000)
    reset = number("RESET_PERFORMANCE_DATA", 0, 1)
    password = os.environ.pop("DEMO_PASSWORD")
    if len(password) < 12 or len(password.encode()) > 72 or '\n' in password or '\r' in password:
        raise ValueError("DEMO_PASSWORD: mínimo 12 caracteres, máximo 72 bytes, sin saltos de línea")
    # Contraseña por stdin, nunca en argv, SQL, archivos ni salida.
    command = ([shutil.which("htpasswd"), "-niBC", "10", "demo"] if shutil.which("htpasswd") else
               ["docker", "run", "--rm", "-i", "httpd:2.4", "htpasswd", "-niBC", "10", "demo"])
    hashed = subprocess.run(command, input=password + "\n", text=True, capture_output=True, check=True)
    del password
    bcrypt = hashed.stdout.strip().split(":", 1)[-1]
    if not re.fullmatch(r"\$2[aby]\$10\$[./A-Za-z0-9]{53}", bcrypt):
        raise ValueError("No se pudo generar BCrypt compatible")
    env = os.environ.copy()
    env["MYSQL_PWD"] = env.pop("DB_PASSWORD")
    mysql = ["docker", "exec", "-i", "-e", "MYSQL_PWD", os.environ["MYSQL_CONTAINER"],
             "mysql", "--batch", "--raw", "--skip-column-names", "--default-character-set=utf8mb4",
             "--user=" + os.environ["DB_USER"], "--database=" + os.environ["DB_NAME"]]

    def query(sql):
        result = subprocess.run(mysql, input=sql, text=True, capture_output=True, env=env)
        if result.returncode:
            # SQL solo contiene el hash, nunca contraseñas. Mantener el error real visible.
            raise RuntimeError(result.stdout + result.stderr)
        return result.stdout

    schema = {}
    for line in query("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_KEY FROM information_schema.columns "
                      "WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION;").splitlines():
        table, column, key = line.split('\t')
        schema.setdefault(table, []).append((column, key))
    for table in TABLES:
        if table not in schema:
            raise ValueError(f"Falta {table}: aplicar las migraciones V1..V30 antes del seed")
    if "return_requests" not in schema or "min_stock" not in dict(schema["products"]):
        raise ValueError("La base debe tener el esquema actual V1..V30")
    fks = [line.split('\t') for line in query(
        "SELECT TABLE_NAME,COLUMN_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME "
        "FROM information_schema.key_column_usage WHERE TABLE_SCHEMA=DATABASE() "
        "AND REFERENCED_TABLE_NAME IS NOT NULL;").splitlines()]
    terms_source = Path(__file__).resolve().parents[1] / (
        "backend/demo/src/main/java/com/transformersas/marketplace/sellers/SellerTerms.java")
    terms = re.search(r'VERSION\s*=\s*"([^"]+)"', terms_source.read_text()).group(1)
    sql = ["SET NAMES utf8mb4; SET SESSION time_zone='+00:00';",
           "SET @locked = GET_LOCK('performance-demo-seed-v1',30);",
           "CREATE TEMPORARY TABLE perf_guard(ok INT NOT NULL CHECK(ok=1));"]

    def emit(statement):
        sql.append(statement)

    def guard(condition, message):
        emit(f"SELECT {literal('ERROR: ' + message)} WHERE NOT ({condition});")
        emit(f"INSERT INTO perf_guard VALUES (IF(({condition}),1,0));")

    def key(table, alias="t"):
        columns = [col for col, kind in schema[table] if kind == "PRI"]
        if not columns:
            raise ValueError(f"{table} no tiene PK; no se puede atribuir/resetear con seguridad")
        return "CONCAT_WS(':'," + ','.join(alias + '.' + identifier(col) for col in columns) + ')'

    def digest(table, alias="t"):
        values = ','.join(literal(col) + ',' + alias + '.' + identifier(col) for col, _ in schema[table])
        return f"SHA2(CAST(JSON_OBJECT({values}) AS CHAR),256)"

    def owned(table, alias="t"):
        return (f"EXISTS (SELECT 1 FROM {MANIFEST} m WHERE m.table_name={literal(table)} "
                f"AND m.row_key={key(table, alias)})")

    guard("@locked=1", "otro seed está ejecutándose")
    # Única tabla auxiliar persistente, propiedad del script; no forma parte de Flyway.
    emit(f"CREATE TABLE IF NOT EXISTS {MANIFEST} (table_name VARCHAR(64) NOT NULL, "
         "row_key VARCHAR(150) NOT NULL, fingerprint VARCHAR(255) NOT NULL, "
         "PRIMARY KEY(table_name,row_key)) ENGINE=InnoDB;")
    emit("START TRANSACTION;")
    config = f"v1/users={users}/products={products}/orders={orders}/terms={terms}"
    if reset:
        # Reset conservador: si el dataset fue usado/modificado, no adivinar qué borrar.
        for table in TABLES:
            guard(f"NOT EXISTS (SELECT 1 FROM {MANIFEST} m LEFT JOIN {identifier(table)} t "
                  f"ON m.row_key={key(table)} WHERE m.table_name={literal(table)} "
                  f"AND (t.{identifier(schema[table][0][0])} IS NULL OR m.fingerprint<>{digest(table)}))",
                  f"{table}: dataset modificado; usar otra BD desechable, no se borró nada")
        # Comprobar incluso CASCADE y SET NULL antes de borrar: ninguna fila ajena se toca.
        for child, column, parent, reference in fks:
            if parent not in TABLES:
                continue
            child_owned = owned(child, "c") if child in TABLES else "FALSE"
            guard(f"NOT EXISTS (SELECT 1 FROM {identifier(child)} c JOIN {identifier(parent)} t "
                  f"ON c.{identifier(column)}=t.{identifier(reference)} WHERE {owned(parent)} "
                  f"AND NOT ({child_owned}))", f"dependencia ajena: {child}.{column}")
        # Referencias del dominio que no tienen FK en las migraciones actuales.
        links = [("order_items", "product_id", "products", "id"),
                 ("user_interactions", "user_id", "user_accounts", "id"),
                 ("claims", "product_id", "products", "id"),
                 ("return_requests", "product_id", "products", "id"),
                 ("SPRING_SESSION", "PRINCIPAL_NAME", "user_accounts", "email"),
                 ("products", "category", "categories", "name")]
        # Referencias polimórficas: basta una referencia a un ID reservado para rechazar reset.
        for table, columns, parents in [
            ("reports", ["reporter_id", "assigned_agent_id", "content_id"], ["user_accounts", "products", "stores"]),
            ("moderation_cases", ["content_id", "assigned_agent_id"], ["products", "stores", "user_accounts"]),
            ("content_moderation_state", ["content_id"], ["products", "stores"]),
            ("audit_events", ["actor_id", "entity_id"], ["user_accounts", "orders", "products", "stores"]),
            ("audit_logs", ["actor_id", "entity_id"], ["user_accounts", "orders", "products", "stores"]),
            ("notifications", ["recipient_id", "reference_id"], ["user_accounts", "orders", "products", "stores"]),
        ]:
            for col in columns:
                if col in dict(schema.get(table, [])):
                    links.extend((table, col, parent, "id") for parent in parents)
        for child, column, parent, reference in links:
            child_owned = owned(child, "c") if child in TABLES else "FALSE"
            guard(f"NOT EXISTS (SELECT 1 FROM {identifier(child)} c JOIN {identifier(parent)} t "
                  f"ON CAST(c.{identifier(column)} AS CHAR)=CAST(t.{identifier(reference)} AS CHAR) "
                  f"WHERE {owned(parent)} AND NOT ({child_owned}))", f"referencia ajena: {child}.{column}")
        for table in reversed(TABLES):
            emit(f"DELETE t FROM {identifier(table)} t WHERE {owned(table)};")
        emit(f"DELETE FROM {MANIFEST};")
    emit(f"SET @new = NOT EXISTS (SELECT 1 FROM {MANIFEST});")
    guard(f"@new OR EXISTS (SELECT 1 FROM {MANIFEST} WHERE table_name='_config' "
          f"AND row_key='v1' AND fingerprint={literal(config)})",
          "volúmenes/versión distintos: usar RESET_PERFORMANCE_DATA=1 o una BD nueva")

    def stage(table, columns, rows):
        emit(f"CREATE TEMPORARY TABLE {table} ({columns});")
        for offset in range(0, len(rows), 500):
            emit(f"INSERT INTO {table} VALUES " + ','.join(
                '(' + ','.join(literal(x) for x in row) + ')' for row in rows[offset:offset+500]) + ';')

    def capture(table, predicate):
        emit(f"INSERT INTO {MANIFEST} SELECT {literal(table)},{key(table)},{digest(table)} "
             f"FROM {identifier(table)} t WHERE @new AND ({predicate});")

    accounts = [(f"buyer{i:03}@marketplace.demo", "COMPRADOR") for i in range(1, users+1)]
    accounts += [("comprador@marketplace.demo", "COMPRADOR"), ("vendedor@marketplace.demo", "VENDEDOR"),
                 ("multirrol@marketplace.demo", "COMPRADOR"), ("soporte@marketplace.demo", "SOPORTE")]
    accounts += [(f"seller{i:03}@marketplace.demo", "VENDEDOR") for i in range(3, 6)]
    stage("perf_accounts", "email VARCHAR(254) PRIMARY KEY, role VARCHAR(16)", accounts)
    guard("NOT @new OR NOT EXISTS (SELECT 1 FROM user_accounts JOIN perf_accounts USING(email))",
          "correo reservado ya existente fuera del manifiesto; no se adopta ni modifica")
    emit("INSERT INTO user_accounts(email,password_hash,status,email_verified_at) "
         f"SELECT email,{literal(bcrypt)},'ACTIVA','2026-01-01' FROM perf_accounts WHERE @new;")
    capture("user_accounts", "t.email IN (SELECT email FROM perf_accounts)")
    emit("INSERT INTO user_account_roles SELECT u.id,a.role FROM perf_accounts a "
         "JOIN user_accounts u USING(email) WHERE @new;")
    emit("INSERT INTO user_account_roles SELECT id,'VENDEDOR' FROM user_accounts "
         "WHERE @new AND email='multirrol@marketplace.demo';")
    capture("user_account_roles", "t.account_id IN (SELECT u.id FROM user_accounts u JOIN perf_accounts USING(email))")
    sellers = ["vendedor@marketplace.demo", "multirrol@marketplace.demo"] + [
        f"seller{i:03}@marketplace.demo" for i in range(3, 6)]
    stage("perf_stores", "n INT PRIMARY KEY,name VARCHAR(100),email VARCHAR(254)",
          [(i, f"Perf demo Tienda {i:02}", email) for i, email in enumerate(sellers, 1)])
    guard("NOT @new OR NOT EXISTS (SELECT 1 FROM stores JOIN perf_stores USING(name))", "nombre de tienda ocupado")
    emit("INSERT INTO stores(name,owner_account_id,description,created_at,updated_at) "
         "SELECT s.name,u.id,'perf-demo-v1','2026-01-01','2026-01-01' FROM perf_stores s "
         "JOIN user_accounts u ON u.email=s.email WHERE @new;")
    capture("stores", "t.name IN (SELECT name FROM perf_stores)")
    emit("INSERT INTO store_shipping_methods SELECT s.id,'STANDARD' FROM stores s JOIN perf_stores p USING(name) WHERE @new;")
    emit("INSERT INTO store_shipping_methods SELECT s.id,'EXPRESS' FROM stores s JOIN perf_stores p USING(name) WHERE @new;")
    capture("store_shipping_methods", "t.store_id IN (SELECT s.id FROM stores s JOIN perf_stores p USING(name))")
    emit("INSERT INTO seller_terms_acceptances SELECT u.id," + literal(terms) + ", '2026-01-01' "
         "FROM user_accounts u JOIN perf_stores p ON p.email=u.email WHERE @new;")
    capture("seller_terms_acceptances", "t.account_id IN (SELECT u.id FROM user_accounts u JOIN perf_stores p ON p.email=u.email)")
    categories = ["Perf demo Moda", "Perf demo Papelería", "Perf demo Hogar", "Perf demo Tecnología"]
    stage("perf_categories", "name VARCHAR(100) PRIMARY KEY", [(name,) for name in categories])
    guard("NOT @new OR NOT EXISTS (SELECT 1 FROM categories JOIN perf_categories USING(name))", "categoría reservada ocupada")
    emit("INSERT INTO categories(name,active) SELECT name,TRUE FROM perf_categories WHERE @new;")
    capture("categories", "t.name IN (SELECT name FROM perf_categories)")
    product_rows = []
    human = ["Camiseta demo", "Libreta demo", "Lámpara demo", "Producto sin stock"]
    for i in range(1, products+1):
        name = f"Producto demo {i:04}" if i <= products-4 else human[i-(products-4)-1]
        product_rows.append((i, name, categories[(i-1) % 4], 5000 + (i*137) % 195000,
                             0 if i == products else 10 + (i*17) % 191, (i-1) % 5 + 1,
                             "PAUSED" if i % 10 == 0 and i <= products-4 else "ACTIVE"))
    stage("perf_products", "n INT PRIMARY KEY,name VARCHAR(255),category VARCHAR(100),price INT,stock INT,store_n INT,status VARCHAR(10)", product_rows)
    emit("INSERT INTO products(name,description,price,stock,category,active,store_id,status) "
         "SELECT p.name,CONCAT('perf-demo-v1/product/',p.n),p.price,p.stock,p.category,p.status='ACTIVE',s.id,p.status "
         "FROM perf_products p JOIN perf_stores ps ON ps.n=p.store_n JOIN stores s ON s.name=ps.name WHERE @new;")
    capture("products", "t.description LIKE 'perf-demo-v1/product/%' AND t.store_id IN "
            "(SELECT s.id FROM stores s JOIN perf_stores p USING(name))")
    # La publicación ACTIVE exige una imagen. SVG embebido: reproducible y sin red externa.
    from urllib.parse import quote
    image = "data:image/svg+xml," + quote(
        '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="160">'
        '<rect width="240" height="160" fill="#e5e7eb"/>'
        '<text x="80" y="85" font-size="24">Demo</text></svg>', safe="")
    emit("INSERT INTO product_images(product_id,position,url) SELECT p.id,0," + literal(image) +
         f" FROM products p WHERE @new AND {owned('products','p')};")
    capture("product_images", f"EXISTS (SELECT 1 FROM products p WHERE p.id=t.product_id AND {owned('products','p')})")
    guard("NOT @new OR NOT EXISTS (SELECT 1 FROM addresses WHERE recipient_name='Perf demo destinatario')", "dirección reservada ocupada")
    emit("INSERT INTO addresses(recipient_name,street,city,department,postal_code,phone) "
         "SELECT 'Perf demo destinatario','Calle ficticia 1','Bogotá','Cundinamarca','110111','3000000000' WHERE @new;")
    capture("addresses", "t.recipient_name='Perf demo destinatario'")
    available = [row[0] for row in product_rows if row[-1] == "ACTIVE" and row[4] > 0]
    order_rows = [(i, f"perf-demo-{i:08}", f"buyer{(i-1) % users+1:03}@marketplace.demo",
                   available[(i-1) % len(available)], i % 3 + 1,
                   "CONFIRMED" if i % 2 else "IN_PREPARATION", "EXPRESS" if i % 4 == 0 else "STANDARD",
                   (i-1) % 180) for i in range(1, orders+1)]
    stage("perf_orders", "n INT PRIMARY KEY,tx VARCHAR(100),email VARCHAR(254),product_n INT,qty INT,status VARCHAR(32),shipping VARCHAR(30),age_days INT", order_rows)
    guard("NOT @new OR NOT EXISTS (SELECT 1 FROM orders o JOIN perf_orders p ON o.transaction_id=p.tx)", "transaction_id reservado ocupado")
    emit("INSERT INTO orders(account_id,status,payment_status,total,store_id,address_id,shipping_method,"
         "delivery_recipient_name,delivery_street,delivery_city,delivery_department,delivery_postal_code,delivery_phone,transaction_id,created_at) "
         "SELECT u.id,x.status,'APPROVED',p.price*x.qty+IF(x.shipping='EXPRESS',20000,10000),p.store_id,a.id,x.shipping,"
         "a.recipient_name,a.street,a.city,a.department,a.postal_code,a.phone,x.tx,"
         "TIMESTAMP('2026-07-01 12:00:00')-INTERVAL x.age_days DAY "
         "FROM perf_orders x JOIN user_accounts u ON u.email=x.email JOIN products p ON p.description=CONCAT('perf-demo-v1/product/',x.product_n) "
         f"AND {owned('products','p')} CROSS JOIN addresses a WHERE @new AND {owned('addresses','a')};")
    capture("orders", "t.transaction_id IN (SELECT tx FROM perf_orders)")
    emit("INSERT INTO order_items(order_id,product_id,product_name,quantity,unit_price,subtotal) "
         "SELECT o.id,p.id,p.name,x.qty,p.price,p.price*x.qty FROM perf_orders x JOIN orders o ON o.transaction_id=x.tx "
         "JOIN products p ON p.description=CONCAT('perf-demo-v1/product/',x.product_n) "
         f"WHERE @new AND {owned('products','p')};")
    capture("order_items", f"EXISTS (SELECT 1 FROM orders o WHERE o.id=t.order_id AND {owned('orders','o')})")
    emit("INSERT INTO order_status_history(order_id,from_status,to_status,actor_type,actor_id,reason,correlation_id,created_at) "
         "SELECT o.id,NULL,'CONFIRMED','BUYER',o.account_id,'Pedido sintético aprobado','perf-demo-v1',o.created_at "
         f"FROM orders o WHERE @new AND {owned('orders','o')};")
    emit("INSERT INTO order_status_history(order_id,from_status,to_status,actor_type,actor_id,reason,correlation_id,created_at) "
         "SELECT o.id,'CONFIRMED','IN_PREPARATION','SELLER',s.owner_account_id,'Preparación sintética','perf-demo-v1',"
         "o.created_at+INTERVAL 1 HOUR FROM orders o JOIN stores s ON s.id=o.store_id "
         f"WHERE @new AND o.status='IN_PREPARATION' AND {owned('orders','o')};")
    capture("order_status_history", f"EXISTS (SELECT 1 FROM orders o WHERE o.id=t.order_id AND {owned('orders','o')})")
    for table, count in [("user_accounts", users+7), ("stores", 5), ("products", products), ("orders", orders), ("order_items", orders)]:
        guard(f"(SELECT COUNT(*) FROM {identifier(table)} t WHERE {owned(table)})={count}", f"cantidad inesperada de {table}")
    emit(f"INSERT INTO {MANIFEST} SELECT '_config','v1',{literal(config)} WHERE @new;")
    emit("COMMIT;")
    emit("SELECT IF(@new,'Dataset creado','Dataset existente: sin cambios');")
    for label, table in [("Usuarios", "user_accounts"), ("Tiendas", "stores"), ("Productos", "products"), ("Pedidos", "orders"), ("Order items", "order_items")]:
        emit(f"SELECT {literal(label)},COUNT(*) FROM {identifier(table)} t WHERE {owned(table)};")
    emit("DO RELEASE_LOCK('performance-demo-seed-v1');")
    print(query('\n'.join(sql)), end='')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"Seed abortado: {error}", file=sys.stderr)
        sys.exit(1)
