USE [TradeManagement]
GO
SET NOCOUNT ON;
GO

-- =========================================================================
-- UC1 seed — credit-card ballast that makes OrdersOverview#build a CPU hotspot.
--
-- credit-card-order-service caches an O(orders x statuses) OrdersOverview but
-- invalidates it on every status write, so GET /v1/orders/{accountId}/status
-- rebuilds it on every request. This seed supplies the data VOLUME that makes
-- each rebuild expensive; deploy/uc1-load/uc1-driver supplies the continuous
-- invalidation. Miss either and there is no visible hotspot (that is exactly
-- what makes "profiling look inactive").
--
-- Shape — MUST match the uc1-driver contract (deploy/uc1-load/uc1-driver.yaml):
--   * @N ballast accounts at FIXED Ids 100001.. (well above loadgen's 1..~290),
--     Origin = 'SEED_CCORDER' so loadgen never logs in and cannot erode them.
--   * EXACTLY ONE order per ballast account. The driver's DELETE step calls
--     deleteOrderForAccountId, which 500s with >1 order/account — never pile
--     multiple orders onto a single ballast account.
--   * ~5 lifecycle statuses per order -> larger O(N^2) scan inside build().
--
-- Idempotent: the NOT EXISTS guards make re-runs a no-op, so run-initialization.sh
-- can (and does) run this on EVERY DB boot. That is what self-heals the ballast
-- after any db-0 reschedule/reset (the StatefulSet's data is persisted, but this
-- also re-establishes the ballast on a fresh volume with no manual seed step).
--
-- Tuning: @N ~= 5000 gives a clear, gradeable hotspot; N < ~1000 is invisible.
-- Raise @N to burn more CPU; lower it if the pod gets CPU-starved to the point
-- of failing health checks.
-- =========================================================================

DECLARE @N  INT = 5000;      -- number of ballast accounts / orders
DECLARE @lo INT = 100001;    -- first ballast account Id (well above loadgen's range)

-------------------------------------------------------------------------------
-- 1) Ballast accounts (identity insert; only the ones still missing)
-------------------------------------------------------------------------------
SET IDENTITY_INSERT [dbo].[Accounts] ON;
;WITH nums AS (
    SELECT TOP (@N) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) - 1 AS i
    FROM sys.all_objects a CROSS JOIN sys.all_objects b
)
INSERT INTO [dbo].[Accounts]
    ([Id],[PackageId],[FirstName],[LastName],[Username],[Email],[HashedPassword],
     [Origin],[CreationDate],[PackageActivationDate],[AccountActive],[Address])
SELECT
    @lo + i,
    1,                                                      -- valid PackageId (Starter)
    'Ballast',
    'Acct' + CAST(@lo + i AS varchar(10)),
    'ballast' + CAST(@lo + i AS varchar(10)),               -- unique Username
    'b' + CAST(@lo + i AS varchar(10)) + '@example.invalid',-- unique Email
    'x',                                                    -- HashedPassword (never logs in)
    'SEED_CCORDER',
    '2023-01-01 00:00:00', '2023-01-01 00:00:00', 1,
    '1 Main St'
FROM nums
WHERE NOT EXISTS (SELECT 1 FROM [dbo].[Accounts] x WHERE x.[Id] = @lo + i);
SET IDENTITY_INSERT [dbo].[Accounts] OFF;

-------------------------------------------------------------------------------
-- 2) Exactly one CreditCardOrders row per ballast account (Id = GUID string)
-------------------------------------------------------------------------------
INSERT INTO [dbo].[CreditCardOrders]
    ([Id],[AccountId],[Email],[Name],[ShippingId],[ShippingAddress],[CardLevel])
SELECT
    LOWER(CONVERT(varchar(36), NEWID())),
    a.[Id],
    'b' + CAST(a.[Id] AS varchar(10)) + '@example.invalid',
    'Ballast ' + CAST(a.[Id] AS varchar(10)),
    NULL,
    '1 Main St',
    CASE a.[Id] % 3 WHEN 0 THEN 'silver' WHEN 1 THEN 'gold' ELSE 'platinum' END
FROM [dbo].[Accounts] a
WHERE a.[Id] BETWEEN @lo AND @lo + @N - 1
  AND NOT EXISTS (SELECT 1 FROM [dbo].[CreditCardOrders] o WHERE o.[AccountId] = a.[Id]);

-------------------------------------------------------------------------------
-- 3) Full 5-step lifecycle history per ballast order (Id is IDENTITY -> omit it).
--    More statuses per order => larger O(orders x statuses) scan in build().
-------------------------------------------------------------------------------
INSERT INTO [dbo].[CreditCardOrderStatus]
    ([CreditCardOrderId],[Timestamp],[Status],[Details])
SELECT
    o.[Id],
    DATEADD(MINUTE, s.seq, CAST('2023-01-01T00:00:00+00:00' AS datetimeoffset(0))),
    s.status,
    NULL
FROM [dbo].[CreditCardOrders] o
JOIN [dbo].[Accounts] a
      ON a.[Id] = o.[AccountId] AND a.[Id] BETWEEN @lo AND @lo + @N - 1
CROSS APPLY (VALUES
    (0, 'order_created'),
    (1, 'card_ordered'),
    (2, 'card_created'),
    (3, 'card_shipped'),
    (4, 'card_delivered')
) AS s(seq, status)
WHERE NOT EXISTS (
    SELECT 1 FROM [dbo].[CreditCardOrderStatus] st WHERE st.[CreditCardOrderId] = o.[Id]
);
GO
