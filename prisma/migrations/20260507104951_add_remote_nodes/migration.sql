-- CreateEnum
CREATE TYPE "NodeAuthType" AS ENUM ('SSH_KEY', 'PASSWORD');

-- CreateEnum
CREATE TYPE "RemoteNodeStatus" AS ENUM ('ONLINE', 'OFFLINE', 'UNKNOWN');

-- AlterTable
ALTER TABLE "CloudResource" ADD COLUMN     "nodeId" TEXT;

-- CreateTable
CREATE TABLE "RemoteNode" (
    "id" TEXT NOT NULL,
    "slug" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "host" TEXT NOT NULL,
    "port" INTEGER NOT NULL DEFAULT 22,
    "username" TEXT NOT NULL,
    "authType" "NodeAuthType" NOT NULL DEFAULT 'SSH_KEY',
    "status" "RemoteNodeStatus" NOT NULL DEFAULT 'UNKNOWN',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "RemoteNode_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "RemoteNode_slug_key" ON "RemoteNode"("slug");

-- AddForeignKey
ALTER TABLE "CloudResource" ADD CONSTRAINT "CloudResource_nodeId_fkey" FOREIGN KEY ("nodeId") REFERENCES "RemoteNode"("id") ON DELETE SET NULL ON UPDATE CASCADE;
