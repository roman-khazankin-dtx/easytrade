#!/bin/bash

# Set up the DB and schema. This runs on EVERY container boot (see entrypoint.sh),
# and the db StatefulSet can run on a PersistentVolume — so on a reschedule this
# executes against an ALREADY-initialized TradeManagement. The base CREATE TABLE /
# INSERT scripts are not idempotent (bare CREATE + unguarded INSERT), so re-running
# them on a populated volume would duplicate the base seed data. Guard them: run the
# base init only when TradeManagement does not yet exist.
#
# The UC1 credit-card ballast seed is idempotent (NOT EXISTS guards) and is applied
# UNCONDITIONALLY on every boot, so the hotspot dataset self-heals on a fresh volume
# and is re-established if anything ever erodes it. See sql-seed-creditcard-history.sql.

SQLCMD=/opt/mssql-tools/bin/sqlcmd
echo "Setting up database and tables"

# Wait for SQL Server to accept connections before probing / initializing.
for i in {1..50}; do
    "$SQLCMD" -S localhost -U sa -P "${SA_PASSWORD}" -d master -Q "SELECT 1" >/dev/null 2>&1 && break
    echo "SQL Server not ready yet..."
    sleep 1
done

DB_EXISTS=$("$SQLCMD" -S localhost -U sa -P "${SA_PASSWORD}" -d master -h -1 -W \
    -Q "SET NOCOUNT ON; SELECT CASE WHEN DB_ID('TradeManagement') IS NULL THEN 0 ELSE 1 END" 2>/dev/null | tr -cd '01' | head -c1)

START=$(date +%s)

if [ "$DB_EXISTS" = "1" ]; then
    echo "TradeManagement already exists (persistent volume) -- skipping base schema/seed init"
else
    echo "Fresh database -- creating schema and base seed data"
    "$SQLCMD" -S localhost -U sa -P "${SA_PASSWORD}" -d master -i create-database.sql
    for f in sql-packages sql-accounts sql-balance sql-products sql-instruments sql-pricing \
             sql-ownedinstruments sql-trades sql-balancehistory sql-creditcardorders \
             sql-creditcardorderstatus sql-creditcards; do
        "$SQLCMD" -S localhost -U sa -P "${SA_PASSWORD}" -d master -i "${f}.sql"
    done
fi

# Always (re)apply the UC1 credit-card ballast seed -- idempotent, so this is a no-op
# when already seeded and self-heals the ballast after any DB reset.
echo "Applying UC1 credit-card ballast seed (idempotent)"
"$SQLCMD" -S localhost -U sa -P "${SA_PASSWORD}" -d master -i sql-seed-creditcard-history.sql

END=$(date +%s)
echo "Setup done"
echo "Setup took $(($END-$START)) seconds"
