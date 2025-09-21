#!/usr/bin/env python3
"""
Real-time click event producer for Kafka
Generates synthetic web click events and sends them to Kafka topic 'web.clicks'
"""

import json
import time
import uuid
import random
import argparse
import logging
from datetime import datetime, timezone
from typing import Dict, Any

from kafka import KafkaProducer
from kafka.errors import KafkaError


class ClickEventGenerator:
    """Generates realistic synthetic click events"""
    
    def __init__(self):
        # Realistic page paths with weights for more realistic traffic patterns
        self.pages = [
            ("/", 0.25),                    # Home page gets most traffic
            ("/search", 0.15),              # Search is popular
            ("/product/42", 0.12),          # Popular product
            ("/product/101", 0.08),         # Another product
            ("/product/205", 0.05),         # Less popular product
            ("/cart", 0.10),                # Shopping cart
            ("/checkout", 0.08),            # Checkout flow
            ("/user/profile", 0.07),        # User profile
            ("/about", 0.03),               # About page
            ("/contact", 0.02),             # Contact page
            ("/help", 0.05)                 # Help section
        ]
        
        # Countries with realistic distribution
        self.countries = [
            ("US", 0.35),
            ("IN", 0.20),
            ("DE", 0.12),
            ("FR", 0.10),
            ("JP", 0.08),
            ("GB", 0.07),
            ("CA", 0.05),
            ("AU", 0.03)
        ]
        
        # Device types with mobile-first distribution
        self.devices = [
            ("mobile", 0.60),
            ("desktop", 0.35),
            ("tablet", 0.05)
        ]
        
        # Extract just the values and weights
        self.page_values, self.page_weights = zip(*self.pages)
        self.country_values, self.country_weights = zip(*self.countries)
        self.device_values, self.device_weights = zip(*self.devices)
        
        # Simulate user sessions
        self.active_users = {}
        self.max_users = 5000
        
    def _weighted_choice(self, values, weights):
        """Make a weighted random choice"""
        return random.choices(values, weights=weights, k=1)[0]
    
    def _get_or_create_user_session(self) -> Dict[str, Any]:
        """Get an existing user session or create a new one"""
        if random.random() < 0.7 and self.active_users:  # 70% chance to use existing user
            user_id = random.choice(list(self.active_users.keys()))
            session = self.active_users[user_id]
            
            # Simulate session expiry (5% chance)
            if random.random() < 0.05:
                del self.active_users[user_id]
                return self._create_new_user_session()
            
            return session
        else:
            return self._create_new_user_session()
    
    def _create_new_user_session(self) -> Dict[str, Any]:
        """Create a new user session"""
        if len(self.active_users) >= self.max_users:
            # Remove a random user to make space
            user_to_remove = random.choice(list(self.active_users.keys()))
            del self.active_users[user_to_remove]
        
        user_id = f"u{random.randint(1, 999999):06d}"
        country = self._weighted_choice(self.country_values, self.country_weights)
        device = self._weighted_choice(self.device_values, self.device_weights)
        
        session = {
            "user_id": user_id,
            "country": country,
            "device": device,
            "last_page": "/",
            "session_start": datetime.now(tz=timezone.utc),
            "page_views": 0
        }
        
        self.active_users[user_id] = session
        return session
    
    def generate_event(self) -> Dict[str, Any]:
        """Generate a single click event"""
        session = self._get_or_create_user_session()
        
        # Choose next page (with some session logic)
        if session["page_views"] == 0:
            # First page view is more likely to be home
            page = "/" if random.random() < 0.4 else self._weighted_choice(self.page_values, self.page_weights)
        else:
            # Subsequent page views follow more realistic patterns
            page = self._weighted_choice(self.page_values, self.page_weights)
        
        referrer = session["last_page"]
        
        # Update session
        session["last_page"] = page
        session["page_views"] += 1
        
        event = {
            "event_id": str(uuid.uuid4()),
            "user_id": session["user_id"],
            "ts": int(datetime.now(tz=timezone.utc).timestamp() * 1000),
            "page": page,
            "referrer": referrer,
            "country": session["country"],
            "device": session["device"]
        }
        
        return event


class ClickProducer:
    """Kafka producer for click events"""
    
    def __init__(self, bootstrap_servers: str = "localhost:9092", topic: str = "click_events"):
        self.topic = topic
        self.generator = ClickEventGenerator()
        
        # Configure Kafka producer
        self.producer = KafkaProducer(
            bootstrap_servers=bootstrap_servers,
            value_serializer=lambda v: json.dumps(v).encode('utf-8'),
            key_serializer=lambda k: k.encode('utf-8') if k else None,
            acks='all',  # Wait for all replicas to acknowledge
            retries=3,   # Retry failed sends
            batch_size=16384,  # Batch size in bytes
            linger_ms=10,      # Wait up to 10ms to batch records
            compression_type='gzip'  # Compress messages
        )
        
        self.events_sent = 0
        self.start_time = time.time()
        
    def send_event(self, event: Dict[str, Any]) -> None:
        """Send a single event to Kafka"""
        try:
            # Use user_id as key for partitioning
            key = event["user_id"]
            
            future = self.producer.send(self.topic, key=key, value=event)
            
            # Optional: Add callback for successful/failed sends
            future.add_callback(self._on_send_success)
            future.add_errback(self._on_send_error)
            
            self.events_sent += 1
            
        except KafkaError as e:
            logging.error(f"Failed to send event: {e}")
            
    def _on_send_success(self, record_metadata):
        """Callback for successful sends"""
        if self.events_sent % 1000 == 0:
            elapsed = time.time() - self.start_time
            rate = self.events_sent / elapsed
            logging.info(f"Sent {self.events_sent} events at {rate:.1f} events/sec")
    
    def _on_send_error(self, excp):
        """Callback for failed sends"""
        logging.error(f"Failed to send event: {excp}")
    
    def run(self, events_per_second: float = 100, duration_seconds: int = None):
        """Run the producer"""
        sleep_time = 1.0 / events_per_second
        
        logging.info(f"Starting producer: {events_per_second} events/sec to topic '{self.topic}'")
        
        start_time = time.time()
        
        try:
            while True:
                if duration_seconds and (time.time() - start_time) > duration_seconds:
                    break
                    
                event = self.generator.generate_event()
                self.send_event(event)
                
                time.sleep(sleep_time)
                
        except KeyboardInterrupt:
            logging.info("Received interrupt signal, shutting down...")
        
        finally:
            # Flush any remaining messages
            self.producer.flush()
            self.producer.close()
            
            elapsed = time.time() - self.start_time
            rate = self.events_sent / elapsed if elapsed > 0 else 0
            logging.info(f"Producer stopped. Sent {self.events_sent} events in {elapsed:.1f}s ({rate:.1f} events/sec)")


def main():
    """Main entry point"""
    parser = argparse.ArgumentParser(description="Generate synthetic click events for Kafka")
    parser.add_argument("--bootstrap-servers", default="localhost:9092",
                       help="Kafka bootstrap servers (default: localhost:9092)")
    parser.add_argument("--topic", default="web.clicks",
                       help="Kafka topic name (default: web.clicks)")
    parser.add_argument("--rate", type=float, default=100,
                       help="Events per second (default: 100)")
    parser.add_argument("--duration", type=int,
                       help="Duration in seconds (default: run forever)")
    parser.add_argument("--log-level", default="INFO",
                       choices=["DEBUG", "INFO", "WARNING", "ERROR"],
                       help="Log level (default: INFO)")
    
    args = parser.parse_args()
    
    # Configure logging
    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format='%(asctime)s - %(levelname)s - %(message)s',
        datefmt='%Y-%m-%d %H:%M:%S'
    )
    
    # Create and run producer
    producer = ClickProducer(
        bootstrap_servers=args.bootstrap_servers,
        topic=args.topic
    )
    
    producer.run(
        events_per_second=args.rate,
        duration_seconds=args.duration
    )


if __name__ == "__main__":
    main()