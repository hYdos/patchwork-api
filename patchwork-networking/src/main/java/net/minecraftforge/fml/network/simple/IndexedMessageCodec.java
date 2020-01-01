package net.minecraftforge.fml.network.simple;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.shorts.Short2ObjectArrayMap;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraftforge.fml.network.NetworkInstance;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import net.minecraft.util.PacketByteBuf;

public class IndexedMessageCodec {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final Marker SIMPLENET = MarkerManager.getMarker("SIMPLENET");
	private final Short2ObjectArrayMap<MessageHandler<?>> indices = new Short2ObjectArrayMap<>();
	private final Object2ObjectArrayMap<Class<?>, MessageHandler<?>> types = new Object2ObjectArrayMap<>();
	private final NetworkInstance networkInstance;

	public IndexedMessageCodec() {
		this(null);
	}

	public IndexedMessageCodec(final NetworkInstance instance) {
		this.networkInstance = instance;
	}

	private static <M> void tryDecode(PacketByteBuf payload, Supplier<NetworkEvent.Context> context, int payloadIndex, MessageHandler<M> codec) {
		if(codec.decoder == null) return;
		Function<PacketByteBuf, M> decoder = codec.decoder;
		M magicPacket = decoder.apply(payload);
		if(payloadIndex != Integer.MIN_VALUE && codec.getLoginIndexSetter() != null) {
			codec.getLoginIndexSetter().accept(magicPacket, payloadIndex);
		}
		codec.messageConsumer.accept(magicPacket, context);
	}

	private static <M> int tryEncode(PacketByteBuf target, M message, MessageHandler<M> codec) {
		if(codec.encoder != null) {
			target.writeByte(codec.index & 0xff);
			codec.encoder.accept(message, target);
		}
		if(codec.loginIndexGetter != null) {
			return codec.loginIndexGetter.apply(message);
		} else {
			return Integer.MIN_VALUE;
		}
	}

	@SuppressWarnings("unchecked")
	public <M> MessageHandler<M> findMessageType(final M msgToReply) {
		return (MessageHandler<M>) types.get(msgToReply.getClass());
	}

	@SuppressWarnings("unchecked")
	<M> MessageHandler<M> findIndex(final short i) {
		return (MessageHandler<M>) indices.get(i);
	}

	public <M> int build(M message, PacketByteBuf target) {
		@SuppressWarnings("unchecked")
		MessageHandler<M> codec = (MessageHandler<M>) types.get(message.getClass());
		if (codec == null) {
			LOGGER.error(SIMPLENET, "Received invalid message {} on channel {}", message.getClass().getName(), Optional.ofNullable(networkInstance).map(NetworkInstance::getChannelName).map(Objects::toString).orElse("MISSING CHANNEL"));
			throw new IllegalArgumentException("Invalid message " + message.getClass().getName());
		}
		return tryEncode(target, message, codec);
	}

	void consume(PacketByteBuf payload, int payloadIndex, Supplier<NetworkEvent.Context> context) {
		if (payload == null) {
			LOGGER.error(SIMPLENET, "Received empty payload on channel {}", Optional.ofNullable(networkInstance).map(NetworkInstance::getChannelName).map(Objects::toString).orElse("MISSING CHANNEL"));
			return;
		}
		short discriminator = payload.readUnsignedByte();
		final MessageHandler<?> messageHandler = indices.get(discriminator);
		if (messageHandler == null) {
			LOGGER.error(SIMPLENET, "Received invalid discriminator byte {} on channel {}", discriminator, Optional.ofNullable(networkInstance).map(NetworkInstance::getChannelName).map(Objects::toString).orElse("MISSING CHANNEL"));
			return;
		}
		tryDecode(payload, context, payloadIndex, messageHandler);
	}

	<M> MessageHandler<M> addCodecIndex(int index, Class<M> messageType, BiConsumer<M, PacketByteBuf> encoder, Function<PacketByteBuf, M> decoder, BiConsumer<M, Supplier<NetworkEvent.Context>> messageConsumer) {
		return new MessageHandler<>(index, messageType, encoder, decoder, messageConsumer);
	}
	// Patchwork: Strip a bunch of unnessisary Optionals
	// Public methods are left alone.
	class MessageHandler<M> {
		private final BiConsumer<M, PacketByteBuf> encoder;
		private final Function<PacketByteBuf, M> decoder;
		private final int index;
		private final BiConsumer<M, Supplier<NetworkEvent.Context>> messageConsumer;
		private final Class<M> messageType;
		@Nullable
		private BiConsumer<M, Integer> loginIndexSetter;
		@Nullable
		private Function<M, Integer> loginIndexGetter;

		public MessageHandler(int index, Class<M> messageType, BiConsumer<M, PacketByteBuf> encoder, Function<PacketByteBuf, M> decoder, BiConsumer<M, Supplier<NetworkEvent.Context>> messageConsumer) {
			this.index = index;
			this.messageType = messageType;
			this.encoder = encoder;
			this.decoder = decoder;
			this.messageConsumer = messageConsumer;
			this.loginIndexGetter = null;
			this.loginIndexSetter = null;
			indices.put((short) (index & 0xff), this);
			types.put(messageType, this);
		}
		@Nullable
		BiConsumer<M, Integer> getLoginIndexSetter() {
			return this.loginIndexSetter;
		}

		void setLoginIndexSetter(BiConsumer<M, Integer> loginIndexSetter) {
			this.loginIndexSetter = loginIndexSetter;
		}

		public Optional<Function<M, Integer>> getLoginIndexGetter() {
			return Optional.ofNullable(this.loginIndexGetter);
		}

		void setLoginIndexGetter(Function<M, Integer> loginIndexGetter) {
			this.loginIndexGetter = loginIndexGetter;
		}

		M newInstance() {
			try {
				return messageType.newInstance();
			} catch (InstantiationException | IllegalAccessException e) {
				LOGGER.error("Invalid login message", e);
				throw new RuntimeException(e);
			}
		}
	}
}
