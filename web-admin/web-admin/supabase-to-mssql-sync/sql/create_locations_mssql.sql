create database TrackingDB
use TrackingDB

CREATE TABLE dbo.locations (
  id BIGINT PRIMARY KEY,
  deviceId NVARCHAR(200) NOT NULL,
  userName NVARCHAR(200) NULL,
  latitude FLOAT NOT NULL,
  longitude FLOAT NOT NULL,
  [timestamp] DATETIME2 NOT NULL
);

CREATE NONCLUSTERED INDEX idx_locations_device_time ON dbo.locations (deviceId, [timestamp] ASC);
