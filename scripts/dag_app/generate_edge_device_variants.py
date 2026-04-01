#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import xml.etree.ElementTree as ET


def _scale_int(text: str, factor: float) -> str:
    return str(int(round(int(text) * factor)))


def scale_devices(root: ET.Element, host_factor: float, vm_factor: float) -> None:
    for host in root.findall(".//host"):
        for tag in ("core", "mips", "ram", "storage"):
            elem = host.find(tag)
            if elem is not None and elem.text:
                elem.text = _scale_int(elem.text, host_factor)
        vms = host.find("VMs")
        if vms is None:
            continue
        for vm in vms.findall("VM"):
            for tag in ("core", "mips", "ram", "storage"):
                elem = vm.find(tag)
                if elem is not None and elem.text:
                    elem.text = _scale_int(elem.text, vm_factor)


def make_hetero(root: ET.Element) -> None:
    for host in root.findall(".//host"):
        vms = host.find("VMs")
        if vms is None:
            continue
        base_vm = vms.find("VM")
        if base_vm is None:
            continue
        base = {}
        for tag in ("core", "mips", "ram", "storage"):
            elem = base_vm.find(tag)
            base[tag] = int(elem.text) if elem is not None and elem.text else 0

        # Build heterogeneous mix that fits host capacity:
        # sum(factors) == 8.0, so totals match the 8x base VM footprint.
        sizes = [
            ("small", 0.5),
            ("small", 0.5),
            ("medium", 1.0),
            ("medium", 1.0),
            ("medium", 1.0),
            ("medium", 1.0),
            ("large", 1.5),
            ("large", 1.5),
        ]

        vmm_attr = base_vm.get("vmm", "Xen")
        # Clear existing VMs
        for child in list(vms):
            vms.remove(child)

        for _, factor in sizes:
            vm = ET.SubElement(vms, "VM", {"vmm": vmm_attr})
            for tag in ("core", "mips", "ram", "storage"):
                elem = ET.SubElement(vm, tag)
                elem.text = _scale_int(str(base[tag]), factor)


def write_xml(root: ET.Element, out_path: Path) -> None:
    tree = ET.ElementTree(root)
    tree.write(out_path, encoding="utf-8", xml_declaration=True)


def main() -> None:
    cfg_dir = Path(__file__).resolve().parent / "config"
    base_path = cfg_dir / "edge_ai_devices_scaled.xml"
    if not base_path.exists():
        raise SystemExit(f"Base XML not found: {base_path}")

    base_root = ET.parse(base_path).getroot()

    # Balanced heterogeneous (same capacity, mixed VM sizes)
    hetero_root = ET.fromstring(ET.tostring(base_root))
    make_hetero(hetero_root)
    write_xml(hetero_root, cfg_dir / "edge_ai_devices_scaled_hetero.xml")

    # Edge-excess homogeneous (double host+vm capacity)
    edge_excess_root = ET.fromstring(ET.tostring(base_root))
    scale_devices(edge_excess_root, host_factor=2.0, vm_factor=2.0)
    write_xml(edge_excess_root, cfg_dir / "edge_ai_devices_edge_excess.xml")

    # Edge-excess heterogeneous
    edge_excess_hetero_root = ET.fromstring(ET.tostring(edge_excess_root))
    make_hetero(edge_excess_hetero_root)
    write_xml(edge_excess_hetero_root, cfg_dir / "edge_ai_devices_edge_excess_hetero.xml")

    print("Generated edge device variants in:", cfg_dir)


if __name__ == "__main__":
    main()
