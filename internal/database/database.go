package database

import (
	"fmt"
	"log"
	"time"

	"github.com/jonathanlopezb/fastertunnel/internal/models"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"
)

// DB es la instancia global de la conexión GORM.
var DB *gorm.DB

// Connect establece la conexión con PostgreSQL y configura el pool de conexiones.
func Connect(databaseURL string) (*gorm.DB, error) {
	if databaseURL == "" {
		return nil, fmt.Errorf("DATABASE_URL is required")
	}

	db, err := gorm.Open(postgres.Open(databaseURL), &gorm.Config{
		Logger: logger.Default.LogMode(logger.Info),
	})
	if err != nil {
		return nil, fmt.Errorf("failed to connect to database: %w", err)
	}

	sqlDB, err := db.DB()
	if err != nil {
		return nil, fmt.Errorf("failed to get sql.DB: %w", err)
	}

	// Pool de conexiones optimizado para un servidor de API REST de alta concurrencia.
	sqlDB.SetMaxOpenConns(25)
	sqlDB.SetMaxIdleConns(5)
	sqlDB.SetConnMaxLifetime(5 * time.Minute)

	log.Println("[DB] Connected to PostgreSQL successfully")

	DB = db
	return db, nil
}

// Migrate ejecuta las migraciones automáticas de todos los modelos.
func Migrate(db *gorm.DB) error {
	log.Println("[DB] Running auto-migrations...")
	err := db.AutoMigrate(
		&models.User{},
		&models.Session{},
		&models.ServerNode{},
		&models.Tweak{},
		&models.WireGuardPeer{},
	)
	if err != nil {
		return fmt.Errorf("auto-migration failed: %w", err)
	}
	log.Println("[DB] Migrations completed successfully")
	return nil
}
