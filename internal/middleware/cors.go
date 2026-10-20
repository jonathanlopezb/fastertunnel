package middleware

import (
	"time"

	"github.com/gin-gonic/gin"
)

// CORS configura los headers de Cross-Origin Resource Sharing.
func CORS() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Origin, Content-Type, Authorization, X-Request-ID")
		c.Header("Access-Control-Max-Age", "86400")

		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}

		c.Next()
	}
}

// RequestLogger registra cada petición entrante con método, ruta y latencia.
func RequestLogger() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		path := c.Request.URL.Path

		c.Next()

		latency := time.Since(start)
		status := c.Writer.Status()

		// Omitir log del health check para no saturar los logs en producción.
		if path != "/api/v1/health" {
			gin.DefaultWriter.Write([]byte(
				time.Now().Format("2006/01/02 15:04:05") +
					" | " + itoa(status) +
					" | " + latency.String() +
					" | " + c.ClientIP() +
					" | " + c.Request.Method +
					" " + path + "\n",
			))
		}
	}
}

func itoa(n int) string {
	return time.Unix(int64(n), 0).Format("000")[:3]
}
