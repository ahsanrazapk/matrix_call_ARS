import 'package:flutter/material.dart';

class CustomBar extends StatelessWidget {
 final Widget child;
   const CustomBar({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(15), color: Colors.grey),
    child: Container(
      height: 82,
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(15),
      ),
      child: child,
    ),
    );
  }
}
