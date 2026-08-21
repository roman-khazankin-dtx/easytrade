USE [TradeManagement]
GO
SET NOCOUNT ON;
GO

-- =========================================================================
-- Seed a large credit-card order history.
--
-- Purpose: give credit-card-order-service's OrdersOverview#build a non-trivial
-- dataset. That method scans EVERY order x EVERY status row system-wide, so
-- these rows inflate the cost of every GET /v1/orders/{accountId}/status call
-- (the cache is defeated on every write, so the overview is rebuilt each time).
-- Without this seed the tables start empty and the O(n^2) recompute is
-- microseconds, so the CPU hotspot never shows.
--
-- The orders hang off dedicated synthetic accounts (Origin = 'SEED_CCORDER')
-- that loadgen never logs into, so the dataset is stable: loadgen revoking a
-- real user's card deletes only that user's orders, never these.
--
-- Tuning knobs (build() cost ~= totalOrders * totalStatuses):
--   @synthAccounts    number of dedicated seed accounts
--   @ordersPerAccount orders per seed account
-- Default 300 * 10 = 3000 orders x 5 statuses = 15000 status rows
--   -> OrdersOverview#build ~= 3000 * 15000 = 45M iterations per recompute
--   (~30-60ms of CPU per GET /status on a throttled container).
-- Raise the knobs to make the hotspot burn more CPU; lower them if the pod
-- gets CPU-starved to the point of failing health checks.
-- =========================================================================

DECLARE @synthAccounts    INT = 300;
DECLARE @ordersPerAccount INT = 10;
DECLARE @totalOrders      INT = @synthAccounts * @ordersPerAccount;

-- 1) dedicated synthetic accounts (never used by loadgen)
;WITH n AS (
    SELECT TOP (@synthAccounts) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS rn
    FROM sys.all_objects a CROSS JOIN sys.all_objects b
)
INSERT INTO [dbo].[Accounts]
    ([PackageId],[FirstName],[LastName],[Username],[Email],[HashedPassword],
     [Origin],[CreationDate],[PackageActivationDate],[AccountActive],[Address])
SELECT
    1,
    'Seed',
    'CardHistory ' + CAST(rn AS varchar(10)),
    'seed_ccorder_' + CAST(rn AS varchar(10)),
    'seed_ccorder_' + CAST(rn AS varchar(10)) + '@example.invalid',
    'x',
    'SEED_CCORDER',
    '2023-01-01 00:00:00',
    '2023-01-01 00:00:00',
    1,
    'Seed address'
FROM n;

-- 2) orders spread round-robin across the synthetic accounts
;WITH acct AS (
    SELECT [Id], ROW_NUMBER() OVER (ORDER BY [Id]) AS rn
    FROM [dbo].[Accounts] WHERE [Origin] = 'SEED_CCORDER'
),
ord AS (
    SELECT TOP (@totalOrders) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS rn
    FROM sys.all_objects a CROSS JOIN sys.all_objects b
)
INSERT INTO [dbo].[CreditCardOrders]
    ([Id],[AccountId],[Email],[Name],[ShippingAddress],[CardLevel])
SELECT
    LOWER(CAST(NEWID() AS nvarchar(36))),
    a.[Id],
    'seed@example.invalid',
    'Seed order ' + CAST(o.rn AS varchar(10)),
    'Seed address',
    'silver'
FROM ord o
JOIN acct a ON a.rn = ((o.rn - 1) % @synthAccounts) + 1;

-- 3) full 5-step lifecycle history for every seeded order
INSERT INTO [dbo].[CreditCardOrderStatus]
    ([CreditCardOrderId],[Timestamp],[Status],[Details])
SELECT
    co.[Id],
    DATEADD(MINUTE, s.seq, CAST('2023-01-01T00:00:00+00:00' AS datetimeoffset(0))),
    s.status,
    'seed'
FROM [dbo].[CreditCardOrders] co
JOIN [dbo].[Accounts] ac
    ON ac.[Id] = co.[AccountId] AND ac.[Origin] = 'SEED_CCORDER'
CROSS JOIN (VALUES
    (0, 'order_created'),
    (1, 'card_ordered'),
    (2, 'card_created'),
    (3, 'card_shipped'),
    (4, 'card_delivered')
) AS s(seq, status);
GO
