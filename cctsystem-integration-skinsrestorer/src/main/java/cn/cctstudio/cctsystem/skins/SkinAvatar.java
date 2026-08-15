package cn.cctstudio.cctsystem.skins;

public record SkinAvatar(byte[] png, String textureHash) {
    public SkinAvatar {
        png = png.clone();
    }

    @Override
    public byte[] png() {
        return png.clone();
    }
}
